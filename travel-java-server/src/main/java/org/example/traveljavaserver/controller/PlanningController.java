package org.example.traveljavaserver.controller;

import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.example.traveljavaserver.auth.CurrentUser;
import org.example.traveljavaserver.planning.*;
import org.example.traveljavaserver.vo.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/planning")
public class PlanningController {
  private final org.springframework.beans.factory.ObjectProvider<PlanningJobs> jobs;

  private PlanningJobs enabledJobs() {
    requireExternal();
    return jobs.getObject();
  }

  @org.springframework.beans.factory.annotation.Value("${planning.external-enabled:false}")
  private boolean externalEnabled;

  private void requireExternal() {
    if (!externalEnabled)
      throw new org.example.traveljavaserver.common.AppException(
          org.springframework.http.HttpStatus.GONE,
          "EXTERNAL_PLANNING_DISABLED",
          "地图查询规划已停用，请回首页使用AI直接生成行程");
  }

  private long owner(Authentication auth) {
    return CurrentUser.require(auth).id();
  }

  @PostMapping("/jobs")
  public Result<PlanTypes.View> create(
      Authentication auth, @Valid @RequestBody PlanTypes.Start request) {
    requireExternal();
    return Result.ok(enabledJobs().create(owner(auth), request));
  }

  @PostMapping("/trips/{id}/revisions")
  public Result<PlanTypes.View> revise(
      Authentication auth, @PathVariable String id, @Valid @RequestBody PlanTypes.Revise request) {
    requireExternal();
    return Result.ok(enabledJobs().revise(owner(auth), id, request));
  }

  @GetMapping("/jobs/{id}")
  public Result<PlanTypes.View> get(Authentication auth, @PathVariable String id) {
    return Result.ok(enabledJobs().get(owner(auth), id));
  }

  @DeleteMapping("/jobs/{id}")
  public Result<Map<String, Boolean>> cancel(Authentication auth, @PathVariable String id) {
    return Result.ok(Map.of("cancelled", enabledJobs().cancel(owner(auth), id)));
  }

  @PostMapping("/jobs/{id}/save")
  public Result<DataViews.TripDetail> save(
      Authentication auth, @PathVariable String id, @Valid @RequestBody PlanTypes.Save request) {
    return Result.ok(enabledJobs().save(owner(auth), id, request));
  }
}
