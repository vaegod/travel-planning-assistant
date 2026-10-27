package org.example.traveljavaserver.planning;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import org.example.traveljavaserver.common.AppException;
import org.example.traveljavaserver.dto.DataRequests;
import org.example.traveljavaserver.service.DataService;
import org.example.traveljavaserver.vo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
    name = "planning.external-enabled",
    havingValue = "true")
public class PlanningJobs {
  private final PlanningEngine engine;
  private final DataService data;
  private final long timeoutMs;
  private final ConcurrentHashMap<String, Work> jobs = new ConcurrentHashMap<>();
  private final ThreadPoolExecutor workers =
      new ThreadPoolExecutor(
          2,
          2,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(8),
          r -> {
            Thread t = new Thread(r, "travel-planning");
            t.setDaemon(true);
            return t;
          },
          new ThreadPoolExecutor.AbortPolicy());
  private final ScheduledExecutorService clock =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "travel-planning-clock");
            t.setDaemon(true);
            return t;
          });

  public PlanningJobs(
      PlanningEngine engine,
      DataService data,
      @Value("${planning.timeout-ms:180000}") long timeoutMs) {
    this.engine = engine;
    this.data = data;
    this.timeoutMs = timeoutMs;
    clock.scheduleAtFixedRate(this::maintain, 1, 1, TimeUnit.SECONDS);
  }

  public PlanTypes.View create(long owner, PlanTypes.Start request) {
    TravelTools.bbox(request.city());
    return submit(new Work(owner, request, null, null, timeoutMs), null);
  }

  public PlanTypes.View revise(long owner, String tripId, PlanTypes.Revise request) {
    var base = data.trip(owner, tripId, null);
    if (base.trip().currentVersion() != request.baseVersion())
      throw new AppException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "行程已更新，请刷新后重新生成修改草稿");
    if (base.trip().days() > 3)
      throw new AppException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_DAYS", "当前真实规划支持1至3天");
    if (Set.of("indoor", "relaxed").contains(request.action())
        && (request.day() == null || request.day() > base.trip().days()))
      throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请选择要修改的有效天数");
    if (request.action().equals("budget") && request.budget() == null)
      throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INPUT", "请填写新的预算上限");
    return submit(
        new Work(
            owner,
            new PlanTypes.Start(base.trip().city(), base.trip().days(), base.trip().budget()),
            tripId,
            request,
            timeoutMs),
        base.plan());
  }

  private synchronized PlanTypes.View submit(Work work, TravelRecommendVO base) {
    maintain();
    long own =
        jobs.values().stream()
            .filter(j -> j.owner == work.owner && Set.of("QUEUED", "RUNNING").contains(j.status))
            .count();
    if (own >= 2 || jobs.size() >= 200)
      throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "PLANNING_BUSY", "已有规划任务，请等待或取消后重试");
    jobs.put(work.id, work);
    work.future =
        new FutureTask<>(
            () -> {
              try {
                synchronized (work) {
                  work.remaining();
                  work.status = "RUNNING";
                }
                var result = engine.execute(work, base);
                synchronized (work) {
                  work.remaining();
                  work.plan = result;
                  work.changes = base == null ? List.of() : PlanRules.diff(base, result);
                  work.progress = "检查完成，请预览并保存";
                  work.status = "READY";
                }
              } catch (CancellationException e) {
                synchronized (work) {
                  if (Set.of("QUEUED", "RUNNING").contains(work.status)) {
                    work.status = "CANCELLED";
                    work.progress = "已取消";
                  }
                }
              } catch (AppException e) {
                synchronized (work) {
                  if (Set.of("QUEUED", "RUNNING").contains(work.status)) {
                    work.status = "ERROR";
                    work.errorCode = e.getErrorCode();
                    work.message = e.getMessage();
                    work.progress = "规划未完成";
                  }
                }
              } catch (Exception e) {
                synchronized (work) {
                  if (Set.of("QUEUED", "RUNNING").contains(work.status)) {
                    work.status = "ERROR";
                    work.errorCode = "PLANNING_FAILED";
                    work.message = "规划失败，已有行程未修改";
                  }
                }
              } finally {
                work.calls.forEach(okhttp3.Call::cancel);
                work.calls.clear();
              }
              return null;
            });
    try {
      workers.execute(work.future);
    } catch (RejectedExecutionException e) {
      jobs.remove(work.id);
      throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "PLANNING_BUSY", "规划队列已满，请稍后重试");
    }
    return view(work);
  }

  private Work owned(long owner, String id) {
    Work work = jobs.get(id);
    if (work == null || work.owner != owner)
      throw new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", "规划任务不存在或已过期");
    return work;
  }

  public PlanTypes.View get(long owner, String id) {
    return view(owned(owner, id));
  }

  public DataViews.TripDetail save(long owner, String id, PlanTypes.Save request) {
    Work work = owned(owner, id);
    synchronized (work) {
      if (work.status.equals("SAVED")) return data.trip(owner, work.savedTripId, work.savedVersion);
      if (!work.status.equals("READY"))
        throw new AppException(HttpStatus.CONFLICT, "DRAFT_NOT_READY", "草稿尚未就绪或已取消，请重新生成");
      var result =
          data.saveVerified(
              owner,
              work.tripId,
              new DataRequests.SaveTrip(
                  request.title(),
                  work.plan,
                  work.revision == null ? null : work.revision.baseVersion()));
      work.savedTripId = result.trip().id();
      work.savedVersion = result.trip().currentVersion();
      work.status = "SAVED";
      work.progress = "已保存";
      return result;
    }
  }

  public boolean cancel(long owner, String id) {
    return cancelWork(owned(owner, id));
  }

  private boolean cancelWork(Work work) {
    synchronized (work) {
      if (work.status.equals("SAVED")
          || work.status.equals("ERROR")
          || work.status.equals("CANCELLED")) return false;
      work.status = "CANCELLED";
      work.progress = "已取消，原行程未修改";
      work.cancel();
      workers.remove(work.future);
      return true;
    }
  }

  public void cancelUser(long owner) {
    jobs.values().stream().filter(w -> w.owner == owner).forEach(this::cancelWork);
  }

  private PlanTypes.View view(Work w) {
    synchronized (w) {
      return new PlanTypes.View(
          w.id,
          w.status,
          w.progress,
          w.errorCode,
          w.message,
          w.savedTripId == null ? w.tripId : w.savedTripId,
          w.revision == null ? null : w.revision.baseVersion(),
          Set.of("READY", "SAVED").contains(w.status) ? w.plan : null,
          w.changes,
          List.copyOf(w.traces),
          w.savedVersion);
    }
  }

  private void maintain() {
    long now = System.nanoTime();
    for (Work work : jobs.values()) {
      if (Set.of("QUEUED", "RUNNING").contains(work.status)
          && TimeUnit.NANOSECONDS.toMillis(now - work.created) > timeoutMs) {
        synchronized (work) {
          if (!Set.of("QUEUED", "RUNNING").contains(work.status)) continue;
          work.status = "ERROR";
          work.errorCode = "PLANNING_TIMEOUT";
          work.message = "规划超过总时限，原行程未修改";
          work.cancel();
          workers.remove(work.future);
        }
      }
      if (!Set.of("QUEUED", "RUNNING").contains(work.status)
          && TimeUnit.NANOSECONDS.toMinutes(now - work.created) >= 15) jobs.remove(work.id, work);
    }
  }

  @PreDestroy
  public void close() {
    jobs.values().forEach(Work::cancel);
    workers.shutdownNow();
    clock.shutdownNow();
  }
}
