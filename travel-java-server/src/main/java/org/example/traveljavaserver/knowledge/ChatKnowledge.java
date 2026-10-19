package org.example.traveljavaserver.knowledge;

import java.util.*;
import java.util.concurrent.CancellationException;
import org.example.traveljavaserver.common.AppException;
import org.springframework.stereotype.Service;
import static org.example.traveljavaserver.knowledge.KnowledgeTypes.*;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "knowledge.enabled", havingValue = "true")
public class ChatKnowledge {
  private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ChatKnowledge.class);
  private final RagEngine engine;
  public ChatKnowledge(RagEngine engine) { this.engine = engine; }
  public Work open(long owner, String requestId) { return new Work(new RagRun(requestId, owner, 30000)); }

  public final class Work implements AutoCloseable {
    private final RagRun run;
    private Work(RagRun run) { this.run = run; }
    public void cancel() { run.cancel(); }
    public ChatEvidence prepare(String city, String message, List<Map<String,String>> history, boolean enabled) {
      var decision = ChatRetrievalPolicy.decide(message, history);
      if (!decision.retrieve()) return fallback("MODEL_ONLY", city,
          "本轮为普通问答或行程建议，直接由模型生成，未检索知识库，未核实实时信息。");
      if (!enabled) return unavailable(city,
          "本轮问题需要资料依据，但已关闭按需查资料。请开启后重试；未查询资料，不会推测规则、私人安排或编造来源。");
      var cities = CityCatalog.mentioned(message);
      String scope = cities.size() > 1 ? "" : cities.size() == 1 ? cities.get(0) : CityCatalog.canonical(city);
      String question = message;
      // Resolve short follow-ups using only the most recent user question; never trust past model citations.
      if (decision.followup()) question = "当前问题：" + message
          + "\n此前用户资料问题（仅用于理解指代）："
          + decision.priorQuestion().substring(0, Math.min(500, decision.priorQuestion().length()));
      try {
        run.remaining();
        Answer answer = engine.answer(run.owner, new Search(question, scope, null, 4), run);
        run.remaining();
        if (!answer.abstained()) return new ChatEvidence("KNOWLEDGE", scope,
            "依据本人知识库资料回答；引用为已核对的本地原文，资料不保证实时有效。", answer.sources(), answer.claims());
        if (answer.reason().contains("静态资料")) return unavailable(scope,
            "知识库为静态资料，无法核实实时状态。请查看官方公告或预约平台；未推测当天开放、价格或余票，也未生成虚构引用。");
        return unavailable(scope,
            answer.sources().isEmpty()
                ? "未找到支持本轮问题的知识库资料，无法依据资料确认。请补充相关资料或调整检索城市；未根据模型常识补写答案。"
                : "现有资料不足以完整回答本轮问题，无法依据资料确认。请补充相关原文或把问题拆小；未根据模型常识补写答案。");
      } catch (CancellationException e) { throw e;
      } catch (RuntimeException e) {
        if (run.cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        LOG.info("chat_retrieval_unavailable requestId={} code={}", run.id,
            e instanceof AppException app ? app.getErrorCode() : "RETRIEVAL_UNAVAILABLE");
        return unavailable(scope, "资料检索或引用核对暂未完成，无法依据资料确认。请稍后重试；未根据模型常识补写答案，也未展示未经核对的引用。");
      }
    }
    @Override public void close() { run.cancel(); }
  }
  private static ChatEvidence unavailable(String city, String notice) {
    return fallback("KNOWLEDGE_UNAVAILABLE", city, notice);
  }
  private static ChatEvidence fallback(String mode, String city, String notice) {
    return new ChatEvidence(mode, CityCatalog.canonical(city), notice, List.of(), List.of());
  }
}
