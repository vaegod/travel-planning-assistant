package org.example.traveljavaserver.knowledge;

import static org.example.traveljavaserver.knowledge.KnowledgeTypes.*;

import java.sql.*;
import java.util.*;
import org.example.traveljavaserver.common.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.*;
import org.springframework.stereotype.Repository;

@Repository
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "knowledge.enabled", havingValue = "true")
public class KnowledgeStore {
  final JdbcTemplate jdbc;

  KnowledgeStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private Doc doc(ResultSet r) throws SQLException {
    return new Doc(
        r.getString("id"),
        r.getString("title"),
        r.getString("city"),
        r.getString("source_url"),
        r.getString("published_on"),
        r.getString("valid_from"),
        r.getString("valid_to"),
        r.getString("origin"),
        r.getString("embedding_model"),
        r.getInt("dimensions"),
        r.getInt("chunk_count"),
        r.getTimestamp("created_at").toInstant().toString());
  }

  public List<Doc> list(long owner) {
    return jdbc.query(
        "SELECT * FROM knowledge_documents WHERE user_id=? ORDER BY created_at DESC,id LIMIT 1000",
        (r, n) -> doc(r),
        owner);
  }

  Doc duplicate(long owner, String fingerprint) {
    var list =
        jdbc.query(
            "SELECT * FROM knowledge_documents WHERE user_id=? AND fingerprint=?",
            (r, n) -> doc(r),
            owner,
            fingerprint);
    return list.isEmpty() ? null : list.get(0);
  }

  public Detail detail(long owner, String id) {
    var docs =
        jdbc.query(
            "SELECT * FROM knowledge_documents WHERE user_id=? AND id=?",
            (r, n) -> Map.entry(doc(r), r.getString("content")),
            owner,
            id);
    if (docs.isEmpty()) throw missing();
    var pieces =
        jdbc.query(
            "SELECT * FROM knowledge_chunks WHERE document_id=? ORDER BY ordinal",
            (r, n) ->
                new Piece(
                    r.getString("id"),
                    r.getInt("ordinal"),
                    r.getInt("start_offset"),
                    r.getInt("end_offset"),
                    r.getString("content")),
            id);
    return new Detail(docs.get(0).getKey(), docs.get(0).getValue(), pieces);
  }

  public void delete(long owner, String id) {
    if (jdbc.update("DELETE FROM knowledge_documents WHERE user_id=? AND id=?", owner, id) == 0)
      throw missing();
  }

  void lockOwner(long owner) {
    jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", Long.class, owner);
  }

  int count(long owner) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM knowledge_documents WHERE user_id=?", Integer.class, owner);
  }

  int chunkCount(long owner) {
    return jdbc.queryForObject(
        "SELECT COALESCE(SUM(chunk_count),0) FROM knowledge_documents WHERE user_id=?",
        Integer.class,
        owner);
  }

  void insert(
      long owner,
      String id,
      Import value,
      String origin,
      String content,
      String fingerprint,
      String model,
      int dimensions,
      List<Piece> chunks,
      List<String> vectors) {
    jdbc.update(
        "INSERT INTO"
            + " knowledge_documents(id,user_id,title,city,source_url,published_on,valid_from,valid_to,origin,content,fingerprint,embedding_model,dimensions,chunk_count)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        owner,
        value.title().trim(),
        value.city(),
        value.sourceUrl() == null ? "" : value.sourceUrl().strip(),
        value.publishedOn(),
        value.validFrom(),
        value.validTo(),
        origin,
        content,
        fingerprint,
        model,
        dimensions,
        chunks.size());
    for (int i = 0; i < chunks.size(); i++) {
      var piece = chunks.get(i);
      jdbc.update(
          "INSERT INTO"
              + " knowledge_chunks(id,document_id,ordinal,start_offset,end_offset,content,embedding)"
              + " VALUES(?,?,?,?,?,?,?)",
          piece.id(),
          id,
          piece.ordinal(),
          piece.startOffset(),
          piece.endOffset(),
          piece.content(),
          vectors.get(i));
    }
  }

  record Candidate(Match match, String vector) {}

  List<Candidate> candidates(long owner, Search search, String model, int dimensions) {
    String date = KnowledgeTypes.date(search.asOf()).toString();
    var aliases = CityCatalog.aliases(search.city());
    boolean all = CityCatalog.canonical(search.city()).isEmpty();
    String cityFilter = all ? "" : " AND d.city IN (" + String.join(",", Collections.nCopies(aliases.size(), "?")) + ")";
    List<Object> args = new ArrayList<>();
    args.add(owner);
    if (!all) args.addAll(aliases);
    args.addAll(List.of(model, dimensions, date, date, date));
    return jdbc.query(
        "SELECT c.id AS chunk_id,c.ordinal,c.content AS piece_content,c.embedding,d.* FROM"
            + " knowledge_chunks c JOIN knowledge_documents d ON d.id=c.document_id WHERE"
            + " d.user_id=?" + cityFilter + " AND d.embedding_model=? AND d.dimensions=? AND"
            + " (d.valid_from IS NULL OR d.valid_from<=?) AND (d.valid_to IS NULL OR d.valid_to>=?)"
            + " AND (d.published_on IS NULL OR d.published_on<=?) ORDER BY d.id,c.ordinal LIMIT"
            + " 8000",
        (r, n) ->
            new Candidate(
                new Match(
                    "",
                    r.getString("chunk_id"),
                    r.getString("id"),
                    r.getInt("ordinal"),
                    r.getString("title"),
                    r.getString("city"),
                    r.getString("source_url"),
                    r.getString("published_on"),
                    r.getTimestamp("created_at").toInstant().toString(),
                    r.getString("origin"),
                    0,
                    r.getString("piece_content")),
                r.getString("embedding")),
        args.toArray());
  }

  static AppException missing() {
    return new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "资料或问答不存在");
  }
}
