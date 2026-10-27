package org.example.traveljavaserver.controller;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.example.traveljavaserver.dto.ChatRequestDTO;
import org.example.traveljavaserver.dto.TravelRequestDTO;
import org.example.traveljavaserver.service.TravelService;
import org.example.traveljavaserver.vo.Result;
import org.example.traveljavaserver.vo.TravelRecommendVO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/travel")
@RequiredArgsConstructor
public class TravelController {
  private final TravelService travelService;

  @GetMapping("/hello")
  public Result<String> hello() {
    return Result.ok("hello world");
  }

  @PostMapping("/recommend")
  public Result<TravelRecommendVO> recommend(@Valid @RequestBody TravelRequestDTO request,
      org.springframework.security.core.Authentication authentication) {
    return Result.ok(
        travelService.recommend(
            org.example.traveljavaserver.auth.CurrentUser.require(authentication).id(),
            request.getRequestId() == null ? UUID.randomUUID().toString() : request.getRequestId(),
            request.getCity().trim(), request.getDays(), request.getBudget()));
  }

  @DeleteMapping("/recommend/{requestId}")
  public Result<Map<String, Boolean>> cancelPlan(@PathVariable String requestId,
      org.springframework.security.core.Authentication authentication) {
    try { UUID.fromString(requestId); }
    catch (IllegalArgumentException error) {
      throw new org.example.traveljavaserver.common.AppException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_INPUT", "requestId必须是UUID");
    }
    return Result.ok(Map.of("cancelled", travelService.cancelPlan(
        org.example.traveljavaserver.auth.CurrentUser.require(authentication).id(), requestId)));
  }

  @PostMapping(value = "/chat", produces = "text/event-stream")
  public ResponseEntity<SseEmitter> chat(
      @Valid @RequestBody ChatRequestDTO request,
      org.springframework.security.core.Authentication authentication) {
    return ResponseEntity.ok()
        .header("Cache-Control", "no-cache")
        .header("X-Accel-Buffering", "no")
        .body(
            travelService.chat(
                org.example.traveljavaserver.auth.CurrentUser.require(authentication).id(),
                request.getConversationId(),
                request.getRequestId(),
                request.getMessage().trim(), request.getCity()));
  }

  @DeleteMapping("/chat/{requestId}")
  public Result<Map<String, Boolean>> cancel(
      @PathVariable String requestId,
      org.springframework.security.core.Authentication authentication) {
    try {
      UUID.fromString(requestId);
    } catch (IllegalArgumentException e) {
      throw new org.example.traveljavaserver.common.AppException(
          org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_INPUT", "requestId必须是UUID");
    }
    return Result.ok(
        Map.of(
            "cancelled",
            travelService.cancel(
                org.example.traveljavaserver.auth.CurrentUser.require(authentication).id(),
                requestId)));
  }
}
