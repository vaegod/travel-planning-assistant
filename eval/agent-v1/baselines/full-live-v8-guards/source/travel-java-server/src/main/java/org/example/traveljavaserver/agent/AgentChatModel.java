package org.example.traveljavaserver.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.example.traveljavaserver.utils.LLMutils;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** OpenAI-compatible wire adapter; Spring AI ChatClient/ToolCallingAdvisor owns the loop. */
final class AgentChatModel implements ChatModel {
  private static final ObjectMapper JSON = new ObjectMapper();
  private final LLMutils llm;
  private final AgentRun run;
  private final int maxRounds;
  AgentChatModel(LLMutils llm, AgentRun run, int maxRounds) {
    this.llm = llm; this.run = run; this.maxRounds = maxRounds;
  }
  @Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
  @Override public ChatResponse call(Prompt prompt) {
    run.remaining();
    // A server-validated draft is terminal; do not spend another model round or lose it to a round limit.
    if (run.draftAccepted && run.report != null && run.report.valid())
      return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("后端已完成草稿校验").build())));
    if (run.modelRounds >= maxRounds) throw AgentRun.error("AGENT_ROUND_LIMIT", "已达到规划轮数上限，请简化要求后重试");
    run.modelRounds++;
    run.progress = "正在规划并选择工具 · 第" + run.modelRounds + "轮";
    var messages = new ArrayList<Map<String, Object>>();
    for (var message : prompt.getInstructions()) {
      if (message instanceof ToolResponseMessage response) {
        for (var result : response.getResponses())
          messages.add(Map.of("role", "tool", "tool_call_id", result.id(), "content", result.responseData()));
      } else {
        var row = new LinkedHashMap<String, Object>();
        row.put("role", message.getMessageType().getValue());
        row.put("content", Objects.toString(message.getText(), ""));
        if (message instanceof AssistantMessage assistant && assistant.hasToolCalls())
          row.put("tool_calls", assistant.getToolCalls().stream().map(t ->
              Map.of("id", t.id(), "type", "function", "function",
                  Map.of("name", t.name(), "arguments", t.arguments()))).toList());
        messages.add(row);
      }
    }
    var definitions = new ArrayList<Map<String, Object>>();
    var allowed = new HashSet<String>();
    if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
      for (var callback : options.getToolCallbacks()) {
        var d = callback.getToolDefinition();
        allowed.add(d.name());
        try {
          definitions.add(Map.of("type", "function", "function", Map.of("name", d.name(),
              "description", d.description(), "parameters", JSON.readTree(d.inputSchema()))));
        } catch (Exception e) { throw new IllegalStateException("Invalid tool schema", e); }
      }
    }
    var call = llm.newToolChatCall(messages, definitions, run.revision == null
        ? llm.planningOutputLimit(run.start.days()) : 4096);
    long remaining = run.remaining();
    call.timeout().timeout(Math.min(120000, remaining), TimeUnit.MILLISECONDS);
    run.track(call);
    try {
      var response = llm.readToolCompletion(call);
      var calls = new ArrayList<AssistantMessage.ToolCall>();
      var ids = new HashSet<String>();
      var returned = response.path("tool_calls");
      if (!returned.isMissingNode() && !returned.isNull() && !returned.isArray())
        throw AgentRun.error("INVALID_TOOL_CALL", "模型返回了无效的工具请求");
      if (returned.size() > 8) throw AgentRun.error("AGENT_TOOL_LIMIT", "单轮工具调用过多");
      for (var t : returned) {
        String id = t.path("id").asText(), name = t.path("function").path("name").asText();
        var arguments = t.path("function").path("arguments");
        if (id.isBlank() || !ids.add(id) || !"function".equals(t.path("type").asText())
            || !allowed.contains(name) || !arguments.isTextual() || arguments.asText().length() > 150000)
          throw AgentRun.error("INVALID_TOOL_CALL", "模型请求了无效或未授权的工具");
        calls.add(new AssistantMessage.ToolCall(id, "function", name, arguments.asText()));
      }
      return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
          .content(response.path("content").asText("")).toolCalls(calls).build())));
    } catch (org.example.traveljavaserver.common.AppException e) {
      if (remaining <= 120000 && e.getErrorCode().equals("MODEL_TIMEOUT"))
        throw AgentRun.error("AGENT_TIMEOUT", "智能规划超过总时限，请减少要求后重试");
      throw e;
    } finally { run.calls.remove(call); }
  }
}

