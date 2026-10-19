package org.example.traveljavaserver.auth;

import lombok.RequiredArgsConstructor;
import org.example.traveljavaserver.knowledge.RagQuestions;
import org.example.traveljavaserver.planning.PlanningJobs;
import org.example.traveljavaserver.service.TravelService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Cleanup is best effort: it must not change a committed account edit into an error. */
@Component
@RequiredArgsConstructor
public class ActiveRequestCleanup {
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(ActiveRequestCleanup.class);
  private final ObjectProvider<TravelService> travel;
  private final ObjectProvider<PlanningJobs> planning;
  private final ObjectProvider<RagQuestions> knowledge;

  public void cancelUser(long owner) {
    attempt("chat", () -> travel.ifAvailable(service -> service.cancelUser(owner)));
    attempt("planning", () -> planning.ifAvailable(service -> service.cancelUser(owner)));
    attempt("knowledge", () -> knowledge.ifAvailable(service -> service.cancelUser(owner)));
  }

  private void attempt(String activity, Runnable action) {
    try {
      action.run();
    } catch (RuntimeException e) {
      LOG.warn(
          "Activity cleanup failed activity={} type={}", activity, e.getClass().getSimpleName());
    }
  }
}
