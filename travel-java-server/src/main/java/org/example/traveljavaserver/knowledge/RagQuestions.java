package org.example.traveljavaserver.knowledge;

import static org.example.traveljavaserver.knowledge.KnowledgeTypes.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.example.traveljavaserver.common.AppException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "knowledge.enabled", havingValue = "true")
public class RagQuestions {
  private final org.springframework.jdbc.core.JdbcTemplate jdbc;
  private final RagEngine engine;
  private final ObjectMapper json = new ObjectMapper();
  private final Map<String, RagRun> active = new ConcurrentHashMap<>();
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(8),
          r -> {
            Thread t = new Thread(r, "travel-rag");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());
  private final ScheduledExecutorService clock =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "rag-clock");
            t.setDaemon(true);
            return t;
          });

  public RagQuestions(RagEngine engine, org.springframework.jdbc.core.JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.engine = engine;
    clock.scheduleAtFixedRate(
        () -> {
          for (var run : active.values())
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - run.created) > run.timeoutMs) {
              finishError(run, "RAG_TIMEOUT", "资料问答超时，请重试");
              run.cancel();
              workers.remove(run.future);
              active.remove(run.id, run);
            }
        },
        1,
        1,
        TimeUnit.SECONDS);
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recover() {
    jdbc.update(
        "UPDATE rag_questions SET status='ERROR',error_code='RAG_INTERRUPTED',message='服务重启，请重新提问'"
            + " WHERE status IN ('QUEUED','RUNNING')");
  }

  public synchronized Question submit(long owner, Search request) {
    if (active.values().stream().filter(r -> r.owner == owner).count() >= 2)
      throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "RAG_BUSY", "已有资料问答正在处理，请等待或取消");
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM rag_questions WHERE user_id=?", Integer.class, owner)
        >= 500)
      throw new AppException(HttpStatus.CONFLICT, "RAG_HISTORY_LIMIT", "资料问答已达500条，请先删除旧记录");
    String id = UUID.randomUUID().toString();
    var run = new RagRun(id, owner, 120000);
    jdbc.update(
        "INSERT INTO rag_questions(id,user_id,question,city,as_of,status)"
            + " VALUES(?,?,?,?,?,'QUEUED')",
        id,
        owner,
        request.question().strip(),
        CityCatalog.canonical(request.city()),
        KnowledgeTypes.date(request.asOf()));
    active.put(id, run);
    run.future =
        new FutureTask<>(
            () -> {
              try {
                run.remaining();
                int started =
                    jdbc.update(
                        "UPDATE rag_questions SET status='RUNNING' WHERE id=? AND user_id=? AND"
                            + " status='QUEUED'",
                        id,
                        owner);
                if (started == 0) return null;
                var result = engine.answer(owner, request, run);
                run.remaining();
                jdbc.update(
                    "UPDATE rag_questions SET status=?,answer_json=? WHERE id=? AND user_id=? AND"
                        + " status='RUNNING'",
                    result.abstained() ? "ABSTAINED" : "READY",
                    json.writeValueAsString(result),
                    id,
                    owner);
              } catch (CancellationException e) {
                jdbc.update(
                    "UPDATE rag_questions SET status='CANCELLED' WHERE id=? AND user_id=? AND"
                        + " status IN ('QUEUED','RUNNING')",
                    id,
                    owner);
              } catch (AppException e) {
                finishError(run, e.getErrorCode(), e.getMessage());
              } catch (Exception e) {
                finishError(run, "RAG_FAILED", "资料问答失败，请重试");
              } finally {
                active.remove(id, run);
                run.calls.forEach(okhttp3.Call::cancel);
                run.calls.clear();
              }
              return null;
            });
    try {
      workers.execute(run.future);
    } catch (RejectedExecutionException e) {
      active.remove(id, run);
      finishError(run, "RAG_BUSY", "资料问答队列已满，请重试");
      throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "RAG_BUSY", "资料问答队列已满，请重试");
    }
    return get(owner, id);
  }

  private void finishError(RagRun run, String code, String text) {
    jdbc.update(
        "UPDATE rag_questions SET status='ERROR',error_code=?,message=? WHERE id=? AND user_id=?"
            + " AND status IN ('QUEUED','RUNNING')",
        code,
        text,
        run.id,
        run.owner);
  }

  public Question get(long owner, String id) {
    var rows =
        jdbc.query(
            "SELECT * FROM rag_questions WHERE user_id=? AND id=?",
            (r, n) -> {
              Answer answer = null;
              if (r.getString("answer_json") != null)
                try {
                  answer = json.readValue(r.getString("answer_json"), Answer.class);
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              return new Question(
                  r.getString("id"),
                  r.getString("question"),
                  r.getString("city"),
                  r.getString("as_of"),
                  r.getString("status"),
                  answer,
                  r.getString("error_code"),
                  r.getString("message"),
                  r.getTimestamp("created_at").toInstant().toString());
            },
            owner,
            id);
    if (rows.isEmpty()) throw KnowledgeStore.missing();
    return rows.get(0);
  }

  public List<Question> list(long owner) {
    return jdbc
        .queryForList(
            "SELECT id FROM rag_questions WHERE user_id=? ORDER BY created_at DESC,id LIMIT 20",
            String.class,
            owner)
        .stream()
        .map(id -> get(owner, id))
        .toList();
  }

  public boolean cancel(long owner, String id) {
    get(owner, id);
    int count =
        jdbc.update(
            "UPDATE rag_questions SET status='CANCELLED' WHERE user_id=? AND id=? AND status IN"
                + " ('QUEUED','RUNNING')",
            owner,
            id);
    var run = active.remove(id);
    if (run != null && run.owner == owner) {
      run.cancel();
      workers.remove(run.future);
    }
    return count > 0;
  }

  public void cancelUser(long owner) {
    active.values().stream()
        .filter(r -> r.owner == owner)
        .toList()
        .forEach(r -> cancel(owner, r.id));
  }

  public void delete(long owner, String id) {
    cancel(owner, id);
    if (jdbc.update("DELETE FROM rag_questions WHERE user_id=? AND id=?", owner, id) == 0)
      throw KnowledgeStore.missing();
  }

  @PreDestroy
  public void close() {
    for (var run : active.values()) {
      finishError(run, "RAG_INTERRUPTED", "服务正在停止，请重新提问");
      run.cancel();
    }
    workers.shutdownNow();
    clock.shutdownNow();
  }
}
