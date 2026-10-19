package org.example.traveljavaserver.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import lombok.Data;

public final class Rows {
  private Rows() {}

  @Data
  public static class User {
    private Long id;
    private String username;
    private String passwordHash;
    private long credentialVersion;
    private Instant createdAt;
  }

  @Data
  public static class Trip {
    private String id;
    private Long userId;
    private String title;
    private String city;
    private Integer days;
    private BigDecimal budget;
    private Integer currentVersion;
    private Instant createdAt;
    private Instant updatedAt;
  }

  @Data
  public static class Version {
    private String id;
    private String tripId;
    private Integer version;
    private String planJson;
    private Instant createdAt;
  }

  @Data
  public static class Conversation {
    private String id;
    private Long userId;
    private String tripId;
    private String title;
    private String contextJson;
    private String activeRequestId;
    private Instant createdAt;
    private Instant updatedAt;
  }

  @Data
  public static class Message {
    private Long seq;
    private String id;
    private String conversationId;
    private Long userId;
    private String requestId;
    private String role;
    private String content;
    private String status;
    private String errorCode;
    private String errorMessage;
    private String evidenceJson;
    private Instant createdAt;
    private Instant updatedAt;
  }
}
