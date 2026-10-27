package org.example.traveljavaserver.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.example.traveljavaserver.auth.CurrentUser;
import org.example.traveljavaserver.dto.DataRequests;
import org.example.traveljavaserver.service.*;
import org.example.traveljavaserver.vo.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DataController {
  private final DataService data;
  private final TravelService travel;

  private long owner(Authentication auth) {
    return CurrentUser.require(auth).id();
  }

  @GetMapping("/trips")
  public Result<DataViews.Page<DataViews.Trip>> trips(
      Authentication auth,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int pageSize) {
    return Result.ok(data.trips(owner(auth), page, pageSize));
  }

  @PostMapping("/trips")
  public Result<DataViews.TripDetail> save(
      Authentication auth, @Valid @RequestBody DataRequests.SaveTrip request) {
    return Result.ok(data.save(owner(auth), null, request));
  }

  @GetMapping("/trips/{id}")
  public Result<DataViews.TripDetail> trip(
      Authentication auth,
      @PathVariable String id,
      @RequestParam(required = false) Integer version) {
    return Result.ok(data.trip(owner(auth), id, version));
  }

  @PutMapping("/trips/{id}")
  public Result<DataViews.TripDetail> update(
      Authentication auth,
      @PathVariable String id,
      @Valid @RequestBody DataRequests.SaveTrip request) {
    return Result.ok(data.save(owner(auth), id, request));
  }

  @DeleteMapping("/trips/{id}")
  public Result<Void> deleteTrip(Authentication auth, @PathVariable String id) {
    data.deleteTrip(owner(auth), id);
    return Result.ok();
  }

  @GetMapping("/conversations")
  public Result<DataViews.Page<DataViews.Conversation>> conversations(
      Authentication auth,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "10") int pageSize) {
    return Result.ok(data.conversations(owner(auth), page, pageSize));
  }

  @PostMapping("/conversations")
  public Result<DataViews.Conversation> create(
      Authentication auth, @Valid @RequestBody DataRequests.NewConversation request) {
    return Result.ok(data.createConversation(owner(auth), request));
  }

  @GetMapping("/conversations/{id}")
  public Result<DataViews.Conversation> conversation(Authentication auth, @PathVariable String id) {
    return Result.ok(data.conversation(owner(auth), id));
  }

  @GetMapping("/conversations/{id}/messages")
  public Result<DataViews.MessagePage> messages(
      Authentication auth,
      @PathVariable String id,
      @RequestParam(required = false) Long before,
      @RequestParam(defaultValue = "20") int pageSize) {
    return Result.ok(data.messages(owner(auth), id, before, pageSize));
  }

  @DeleteMapping("/conversations/{id}")
  public Result<Void> deleteConversation(Authentication auth, @PathVariable String id) {
    long user = owner(auth);
    travel.cancelConversation(user, id);
    data.deleteConversation(user, id);
    return Result.ok();
  }
}
