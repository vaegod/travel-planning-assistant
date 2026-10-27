package org.example.traveljavaserver.controller;

import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.example.traveljavaserver.agent.*;
import org.example.traveljavaserver.auth.CurrentUser;
import org.example.traveljavaserver.vo.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent")
public class AgentController {
  private final AgentJobs jobs;
  private long owner(Authentication auth) { return CurrentUser.require(auth).id(); }
  @GetMapping("/capabilities")
  public Result<Map<String, Object>> capabilities(Authentication auth) { owner(auth); return Result.ok(jobs.capabilities()); }
  @PostMapping("/jobs")
  public Result<AgentTypes.View> create(Authentication auth, @Valid @RequestBody AgentTypes.Start request) {
    return Result.ok(jobs.create(owner(auth), request));
  }
  @PostMapping("/trips/{id}/revisions")
  public Result<AgentTypes.View> revise(Authentication auth, @PathVariable String id, @Valid @RequestBody AgentTypes.Revise request) {
    return Result.ok(jobs.revise(owner(auth), id, request));
  }
  @GetMapping("/jobs/{id}")
  public Result<AgentTypes.View> get(Authentication auth, @PathVariable String id) { return Result.ok(jobs.get(owner(auth), id)); }
  @DeleteMapping("/jobs/{id}")
  public Result<Map<String, Boolean>> cancel(Authentication auth, @PathVariable String id) {
    return Result.ok(Map.of("cancelled", jobs.cancel(owner(auth), id)));
  }
  @PostMapping("/jobs/{id}/save")
  public Result<DataViews.TripDetail> save(Authentication auth, @PathVariable String id, @Valid @RequestBody AgentTypes.Save request) {
    return Result.ok(jobs.save(owner(auth), id, request));
  }
}

