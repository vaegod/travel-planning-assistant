package org.example.traveljavaserver.knowledge;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.example.traveljavaserver.utils.ModelTrace;

public final class KnowledgeTypes {
  private KnowledgeTypes() {}

  public record Import(
      @NotBlank @Size(max = 160) String title,
      @NotBlank @Size(max = 50) @Pattern(regexp = "[^\\p{Cntrl}]+") String city,
      @Size(max = 1000) String sourceUrl,
      @PastOrPresent LocalDate publishedOn,
      LocalDate validFrom,
      LocalDate validTo,
      @NotBlank @Size(min = 30, max = 12000) String content) {}

  public record Search(
      @NotBlank @Size(max = 2000) String question,
      @Size(max = 50) @Pattern(regexp = "[^\\p{Cntrl}]*") String city,
      LocalDate asOf,
      @Min(1) @Max(8) Integer topK) {}

  public record Doc(
      String id,
      String title,
      String city,
      String sourceUrl,
      String publishedOn,
      String validFrom,
      String validTo,
      String origin,
      String embeddingModel,
      int dimensions,
      int chunkCount,
      String createdAt) {}

  public record Piece(String id, int ordinal, int startOffset, int endOffset, String content) {}

  public record Detail(Doc document, String content, List<Piece> chunks) {}

  public record Imported(Doc document, boolean duplicate) {}

  public record Seed(int imported, int duplicates, List<String> failures) {}

  public record Match(
      String ref,
      String chunkId,
      String documentId,
      int ordinal,
      String title,
      String city,
      String sourceUrl,
      String publishedOn,
      String importedAt,
      String origin,
      double score,
      String content) {}

  public record Retrieved(
      List<Match> matches, String embeddingModel, int dimensions, String asOf, long durationMs) {}

  public record Citation(String ref, String quote) {}

  public record Claim(String text, List<Citation> citations) {}

  public record Answer(
      boolean abstained,
      String reason,
      List<Claim> claims,
      List<Match> sources,
      String notice,
      List<ModelTrace.Entry> modelCalls) {}

  public record Question(
      String id,
      String question,
      String city,
      String asOf,
      String status,
      Answer answer,
      String errorCode,
      String message,
      String createdAt) {}

  public static LocalDate date(LocalDate value) {
    return value == null ? LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")) : value;
  }
}
