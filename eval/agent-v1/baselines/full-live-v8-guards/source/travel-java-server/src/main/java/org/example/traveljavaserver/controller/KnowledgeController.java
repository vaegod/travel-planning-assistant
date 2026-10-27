package org.example.traveljavaserver.controller;

import jakarta.validation.Valid;
import java.util.*;
import org.example.traveljavaserver.auth.CurrentUser;
import org.example.traveljavaserver.knowledge.*;
import org.example.traveljavaserver.vo.Result;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "knowledge.enabled", havingValue = "true")
@RequestMapping("/api/knowledge")
public class KnowledgeController {
  private final KnowledgeService service;
  private final KnowledgeStore store;
  private final RagQuestions questions;

  public KnowledgeController(
      KnowledgeService service, KnowledgeStore store, RagQuestions questions) {
    this.service = service;
    this.store = store;
    this.questions = questions;
  }

  private long owner(Authentication auth) {
    return CurrentUser.require(auth).id();
  }

  @GetMapping("/documents")
  public Result<?> documents(Authentication auth) {
    return Result.ok(store.list(owner(auth)));
  }

  @GetMapping("/documents/{id}")
  public Result<?> document(Authentication auth, @PathVariable String id) {
    return Result.ok(store.detail(owner(auth), id));
  }

  @PostMapping("/documents")
  public Result<?> add(Authentication auth, @Valid @RequestBody KnowledgeTypes.Import request) {
    return Result.ok(service.importDocument(owner(auth), request));
  }

  @DeleteMapping("/documents/{id}")
  public Result<?> delete(Authentication auth, @PathVariable String id) {
    store.delete(owner(auth), id);
    return Result.ok(null);
  }

  @PostMapping("/seed")
  public Result<?> seed(Authentication auth) {
    return Result.ok(service.seed(owner(auth)));
  }

  @PostMapping("/search")
  public Result<?> search(Authentication auth, @Valid @RequestBody KnowledgeTypes.Search request) {
    return Result.ok(service.search(owner(auth), request));
  }

  @PostMapping("/questions")
  public Result<?> ask(Authentication auth, @Valid @RequestBody KnowledgeTypes.Search request) {
    return Result.ok(questions.submit(owner(auth), request));
  }

  @GetMapping("/questions")
  public Result<?> history(Authentication auth) {
    return Result.ok(questions.list(owner(auth)));
  }

  @GetMapping("/questions/{id}")
  public Result<?> answer(Authentication auth, @PathVariable String id) {
    return Result.ok(questions.get(owner(auth), id));
  }

  @PostMapping("/questions/{id}/cancel")
  public Result<?> cancel(Authentication auth, @PathVariable String id) {
    return Result.ok(Map.of("cancelled", questions.cancel(owner(auth), id)));
  }

  @DeleteMapping("/questions/{id}")
  public Result<?> deleteQuestion(Authentication auth, @PathVariable String id) {
    questions.delete(owner(auth), id);
    return Result.ok(null);
  }
}
