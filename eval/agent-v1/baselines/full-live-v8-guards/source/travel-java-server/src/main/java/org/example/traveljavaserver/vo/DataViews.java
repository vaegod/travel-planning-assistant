package org.example.traveljavaserver.vo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.example.traveljavaserver.persistence.Rows;

public final class DataViews {
  private DataViews() {}

  public record Page<T>(List<T> items, long total, int page, int pageSize) {}

  public record Trip(
      String id,
      String title,
      String city,
      int days,
      BigDecimal budget,
      int currentVersion,
      Instant createdAt,
      Instant updatedAt) {
    public static Trip of(Rows.Trip t) {
      return new Trip(
          t.getId(),
          t.getTitle(),
          t.getCity(),
          t.getDays(),
          t.getBudget(),
          t.getCurrentVersion(),
          t.getCreatedAt(),
          t.getUpdatedAt());
    }
  }

  public record Version(int version, Instant createdAt) {}

  public record TripDetail(Trip trip, TravelRecommendVO plan, List<Version> versions) {}

  public record Conversation(
      String id,
      String tripId,
      String title,
      Map<String, Object> context,
      String activeRequestId,
      Instant createdAt,
      Instant updatedAt) {}

  public record Message(
      String id,
      long seq,
      String requestId,
      String role,
      String content,
      String status,
      String errorCode,
      String errorMessage,
      org.example.traveljavaserver.knowledge.ChatEvidence evidence,
      Instant createdAt) {
    public static Message of(Rows.Message m) {
      return new Message(
          m.getId(),
          m.getSeq(),
          m.getRequestId(),
          m.getRole(),
          m.getContent(),
          m.getStatus(),
          m.getErrorCode(),
          m.getErrorMessage(),
          evidence(m.getEvidenceJson()),
          m.getCreatedAt());
    }
    private static org.example.traveljavaserver.knowledge.ChatEvidence evidence(String value) {
      if (value == null) return null;
      try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(value, org.example.traveljavaserver.knowledge.ChatEvidence.class); }
      catch (Exception e) { throw new IllegalStateException("Invalid stored evidence", e); }
    }
  }

  public record MessagePage(List<Message> items, Long nextBefore, boolean hasMore) {}
}
