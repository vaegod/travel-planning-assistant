package org.example.traveljavaserver.knowledge;

import static org.example.traveljavaserver.knowledge.KnowledgeTypes.*;

import com.fasterxml.jackson.databind.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.example.traveljavaserver.common.AppException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "knowledge.enabled", havingValue = "true")
public class KnowledgeService {
  private final KnowledgeStore store;
  private final TravelEmbedding embeddings;
  private final TransactionTemplate transaction;
  private final ObjectMapper json = new ObjectMapper();
  private final Semaphore imports = new Semaphore(2);
  private final Semaphore searches = new Semaphore(2);
  private final Set<Long> importing = ConcurrentHashMap.newKeySet();
  private final double threshold;

  public KnowledgeService(
      KnowledgeStore store,
      TravelEmbedding embeddings,
      org.springframework.transaction.PlatformTransactionManager manager,
      @Value("${knowledge.min-score:0.55}") double threshold) {
    this.store = store;
    this.embeddings = embeddings;
    this.transaction = new TransactionTemplate(manager);
    this.threshold = threshold;
    if (threshold < 0 || threshold > 1)
      throw new IllegalArgumentException("Invalid retrieval threshold");
  }

  private void acquire(long owner) {
    if (!importing.add(owner)) throw busy();
    if (!imports.tryAcquire()) {
      importing.remove(owner);
      throw busy();
    }
  }

  private AppException busy() {
    return new AppException(HttpStatus.TOO_MANY_REQUESTS, "IMPORT_BUSY", "已有资料正在导入，请稍后重试");
  }

  private void release(long owner) {
    imports.release();
    importing.remove(owner);
  }

  public Imported importDocument(long owner, Import value) {
    acquire(owner);
    try {
      return importOne(owner, value, "USER");
    } finally {
      release(owner);
    }
  }

  private Imported importOne(long owner, Import value, String origin) {
    value = new Import(value.title(), CityCatalog.canonical(value.city()), value.sourceUrl(),
        value.publishedOn(), value.validFrom(), value.validTo(), value.content());
    final Import normalized = value;
    String sourceUrl = value.sourceUrl() == null ? "" : value.sourceUrl().strip();
    try {
      var uri = java.net.URI.create(sourceUrl);
      if (!sourceUrl.isEmpty()
          && (!"https".equalsIgnoreCase(uri.getScheme())
              || uri.getHost() == null
              || uri.getUserInfo() != null
              || uri.getFragment() != null)) throw new IllegalArgumentException();
    } catch (Exception e) {
      throw new AppException(
          HttpStatus.BAD_REQUEST, "INVALID_SOURCE_URL", "来源需为不含账号或锚点的HTTPS原页链接，仅作元数据，不自动抓取网页");
    }
    if (value.validFrom() != null
        && value.validTo() != null
        && value.validFrom().isAfter(value.validTo()))
      throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_DATES", "资料有效期起止顺序不正确");
    String content = value.content().replace("\r\n", "\n").replace('\r', '\n').strip();
    if (content.length() < 30)
      throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT", "资料正文至少30个字符");
    if (content.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\t'))
      throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_DOCUMENT", "资料需为不含二进制控制字符的文本");
    List<String> fingerprints = CityCatalog.aliases(value.city()).stream()
        .map(city -> fingerprint(normalized, city, sourceUrl, origin, content)).toList();
    String fingerprint = fingerprints.get(0);
    for (String candidate : fingerprints) {
      var existing = store.duplicate(owner, candidate);
      if (existing != null) return new Imported(existing, true);
    }
    if (store.count(owner) >= 1000)
      throw new AppException(HttpStatus.CONFLICT, "KNOWLEDGE_LIMIT", "每个账号最多1000份资料，请先删除不需要的资料");
    List<Piece> chunks = split(content);
    if (store.chunkCount(owner) + chunks.size() > 8000)
      throw new AppException(HttpStatus.CONFLICT, "KNOWLEDGE_LIMIT", "每个账号最多8000个资料片段");
    List<String> vectors = new ArrayList<>();
    var run = new RagRun(UUID.randomUUID().toString(), owner, 60000);
    RagRun.CURRENT.set(run);
    try {
      for (int offset = 0; offset < chunks.size(); offset += 16) {
        run.remaining();
        var batch = chunks.subList(offset, Math.min(offset + 16, chunks.size()));
        var values =
            embeddings.embed(batch.stream().map(c -> normalized.title() + "\n" + c.content()).toList());
        for (float[] vector : values) vectors.add(json.writeValueAsString(vector));
      }
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    } finally {
      RagRun.CURRENT.remove();
      run.cancel();
    }
    String id = UUID.randomUUID().toString();
    return transaction.execute(
        status -> {
          store.lockOwner(owner);
          for (String candidate : fingerprints) {
            var duplicate = store.duplicate(owner, candidate);
            if (duplicate != null) return new Imported(duplicate, true);
          }
          if (store.count(owner) >= 1000)
            throw new AppException(HttpStatus.CONFLICT, "KNOWLEDGE_LIMIT", "资料数量已达上限");
          if (store.chunkCount(owner) + chunks.size() > 8000)
            throw new AppException(HttpStatus.CONFLICT, "KNOWLEDGE_LIMIT", "资料片段数量已达上限");
          store.insert(
              owner,
              id,
              normalized,
              origin,
              content,
              fingerprint,
              embeddings.model(),
              embeddings.dimensions(),
              chunks,
              vectors);
          return new Imported(store.detail(owner, id).document(), false);
        });
  }

  private String fingerprint(Import value, String city, String sourceUrl, String origin, String content) {
    String metadata = value.title().trim() + "\n" + city + "\n" + sourceUrl + "\n"
        + value.publishedOn() + "\n" + value.validFrom() + "\n" + value.validTo() + "\n"
        + origin + "\n" + embeddings.model() + "\n" + embeddings.dimensions() + "\n" + content;
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(metadata.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) { throw new IllegalStateException(e); }
  }

  public static List<Piece> split(String content) {
    List<Piece> result = new ArrayList<>();
    for (int start = 0; start < content.length(); ) {
      int end = Math.min(content.length(), start + 450);
      if (end < content.length() && Character.isHighSurrogate(content.charAt(end - 1))) end--;
      result.add(
          new Piece(
              UUID.randomUUID().toString(),
              result.size() + 1,
              start,
              end,
              content.substring(start, end)));
      if (end == content.length()) break;
      start = end - 80;
      if (Character.isLowSurrogate(content.charAt(start))) start--;
    }
    return List.copyOf(result);
  }

  public Seed seed(long owner) {
    acquire(owner);
    try {
      List<Import> values = corpus();
      int added = 0, duplicates = 0;
      List<String> failures = new ArrayList<>();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
      for (var item : values)
        try {
          if (System.nanoTime() > deadline) {
            failures.add("批量导入达到时限，已成功的资料保留，可以再次导入剩余项");
            break;
          }
          if (importOne(owner, item, "OFFICIAL_DIGEST").duplicate()) duplicates++;
          else added++;
        } catch (AppException e) {
          failures.add(item.title() + "：" + e.getMessage());
          break;
        }
      return new Seed(added, duplicates, failures);
    } finally {
      release(owner);
    }
  }

  public List<Import> corpus() {
    var resource = new ClassPathResource("knowledge/hangzhou.json");
    if (!resource.exists()) throw new AppException(HttpStatus.GONE, "BUILTIN_KNOWLEDGE_REMOVED",
        "内置公开资料已移除，请自行导入有权使用的资料。");
    try (var input = resource.getInputStream()) {
      var root = json.readTree(input);
      List<Import> values = new ArrayList<>();
      for (var item : root) {
        values.add(
            new Import(
                item.path("title").asText(),
                "杭州",
                item.path("sourceUrl").asText(),
                item.hasNonNull("publishedOn")
                    ? LocalDate.parse(item.path("publishedOn").asText())
                    : null,
                null,
                null,
                item.path("content").asText()));
      }
      return values;
    } catch (Exception e) {
      throw new IllegalStateException("Cannot load curated knowledge", e);
    }
  }

  public Retrieved search(long owner, Search request) {
    var run = new RagRun(UUID.randomUUID().toString(), owner, 20000);
    RagRun.CURRENT.set(run);
    try {
      return retrieve(owner, request);
    } finally {
      RagRun.CURRENT.remove();
      run.cancel();
    }
  }

  Retrieved retrieve(long owner, Search request) {
    if (!searches.tryAcquire()) throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "RETRIEVAL_BUSY", "资料检索繁忙，请稍后重试");
    try { return retrieveAvailable(owner, request); } finally { searches.release(); }
  }

  private Retrieved retrieveAvailable(long owner, Search request) {
    long start = System.nanoTime();
    var candidates = store.candidates(owner, request, embeddings.model(), embeddings.dimensions());
    if (candidates.isEmpty())
      return new Retrieved(
          List.of(),
          embeddings.model(),
          embeddings.dimensions(),
          KnowledgeTypes.date(request.asOf()).toString(),
          0);
    float[] query = embeddings.embed(request.question());
    List<Match> found = new ArrayList<>();
    for (var row : candidates) {
      try {
        float[] vector = json.readValue(row.vector(), float[].class);
        double score = cosine(query, vector);
        if (score < threshold) continue;
        var m = row.match();
        found.add(
            new Match(
                "",
                m.chunkId(),
                m.documentId(),
                m.ordinal(),
                m.title(),
                m.city(),
                m.sourceUrl(),
                m.publishedOn(),
                m.importedAt(),
                m.origin(),
                score,
                m.content()));
      } catch (Exception e) {
        throw RagRun.error("INVALID_STORED_VECTOR", "资料向量与当前模型不匹配，请重新导入");
      }
    }
    found.sort(Comparator.comparingDouble(Match::score).reversed().thenComparing(Match::chunkId));
    int count = request.topK() == null ? 4 : request.topK();
    List<Match> selected = new ArrayList<>();
    Map<String, Integer> documents = new HashMap<>();
    for (var m : found) {
      if (documents.merge(m.documentId(), 1, Integer::sum) > 2) continue;
      selected.add(
          new Match(
              "C" + (selected.size() + 1),
              m.chunkId(),
              m.documentId(),
              m.ordinal(),
              m.title(),
              m.city(),
              m.sourceUrl(),
              m.publishedOn(),
              m.importedAt(),
              m.origin(),
              m.score(),
              m.content()));
      if (selected.size() >= count) break;
    }
    return new Retrieved(
        selected,
        embeddings.model(),
        embeddings.dimensions(),
        KnowledgeTypes.date(request.asOf()).toString(),
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
  }

  public static double cosine(float[] a, float[] b) {
    if (a.length != b.length) throw new IllegalArgumentException("Vector dimension mismatch");
    double dot = 0, left = 0, right = 0;
    for (int i = 0; i < a.length; i++) {
      if (!Float.isFinite(a[i]) || !Float.isFinite(b[i]))
        throw new IllegalArgumentException("Invalid vector");
      dot += (double) a[i] * b[i];
      left += (double) a[i] * a[i];
      right += (double) b[i] * b[i];
    }
    if (left == 0 || right == 0) throw new IllegalArgumentException("Zero vector");
    return Math.max(-1, Math.min(1, dot / Math.sqrt(left * right)));
  }
}
