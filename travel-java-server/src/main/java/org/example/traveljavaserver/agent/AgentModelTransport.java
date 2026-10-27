package org.example.traveljavaserver.agent;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.example.traveljavaserver.common.AppException;
import org.example.traveljavaserver.utils.*;

/** Recovery of one unconsumed model response. Tools remain exclusively owned by Spring AI. */
final class AgentModelTransport {
  static final long ATTEMPT_TIMEOUT_MS=75000, MIN_RECOVERY_MS=20000;
  static final int MAX_RECOVERIES=1;
  private AgentModelTransport() {}
  static JsonNode complete(LLMutils llm, AgentRun run, int maxRounds, long timeoutMs,
      List<Map<String,Object>> messages, List<Map<String,Object>> definitions, int outputLimit) {
    String recoveryOf=null;
    while(true) {
      long remaining=run.remaining();
      if(run.modelRounds>=maxRounds)
        throw AgentRun.error("AGENT_ROUND_LIMIT","已达到规划轮数上限，请简化要求后重试");
      run.modelRounds++;
      run.progress=(recoveryOf==null?"正在请求模型":"正在恢复模型连接")+" · 第"+run.modelRounds+"轮";
      var call=recoveryOf==null?llm.newToolChatCall(messages,definitions,outputLimit)
          :llm.newToolChatCall(messages,definitions,outputLimit,recoveryOf);
      String attemptId=ModelTrace.describe(call,run.modelRounds);
      call.timeout().timeout(Math.min(timeoutMs,remaining),TimeUnit.MILLISECONDS);
      run.track(call);
      try {
        var response=llm.readToolCompletion(call);
        run.remaining(); // Cancellation and total deadline win even when the network just completed.
        return response;
      } catch(AppException error) {
        run.remaining();
        if(remaining<=timeoutMs&&error.getErrorCode().equals("MODEL_TIMEOUT"))
          throw AgentRun.error("AGENT_TIMEOUT","智能规划超过总时限，请减少要求后重试");
        if(run.recoveryAttempts>=MAX_RECOVERIES||run.modelRounds>=maxRounds
            ||run.remaining()<MIN_RECOVERY_MS||!ModelTrace.recoverable(call,error.getErrorCode()))throw error;
        run.recoveryAttempts++;
        run.trace("recover_model","retry",System.nanoTime(),
            "模型请求未取得可用响应，使用新连接恢复一次；计入轮数和总时限，已完成工具保持原结果");
        recoveryOf=attemptId;
      } finally { run.calls.remove(call); }
    }
  }
}
