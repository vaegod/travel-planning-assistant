package org.example.traveljavaserver.vo;

/** Every JSON SSE event uses the same envelope. Null fields are not meaningful. */
public record StreamEvent(
    String type, String requestId, String content, String code, String message,
    org.example.traveljavaserver.knowledge.ChatEvidence evidence) {
  public static StreamEvent start(String id) {
    return new StreamEvent("start", id, null, null, null, null);
  }

  public static StreamEvent chunk(String id, String content) {
    return new StreamEvent("chunk", id, content, null, null, null);
  }

  public static StreamEvent done(String id) {
    return new StreamEvent("done", id, null, null, null, null);
  }

  public static StreamEvent error(String id, String code, String message) {
    return new StreamEvent("error", id, null, code, message, null);
  }
  public static StreamEvent evidence(String id, org.example.traveljavaserver.knowledge.ChatEvidence evidence) {
    return new StreamEvent("evidence", id, null, null, null, evidence);
  }
}
