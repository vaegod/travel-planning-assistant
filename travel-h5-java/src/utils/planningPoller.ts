import type { PlanningJob } from "../types";

export function isPlanningPending(status: PlanningJob["status"]) {
  return status === "QUEUED" || status === "RUNNING";
}

// A new refresh or leaving the page invalidates any earlier in-flight response.
export function createPlanningPoller<T extends PlanningJob = PlanningJob>(
  load: () => Promise<T>,
  receive: (job: T) => void,
  fail: (error: unknown) => void,
  interval = 1000,
) {
  let generation = 0;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const stop = () => {
    generation++;
    if (timer) clearTimeout(timer);
    timer = undefined;
  };
  const run = async (current: number) => {
    try {
      const job = await load();
      if (current !== generation) return;
      receive(job);
      if (isPlanningPending(job.status))
        timer = setTimeout(() => void run(current), interval);
    } catch (error) {
      if (current === generation) fail(error);
    }
  };
  return {
    stop,
    refresh() {
      stop();
      return run(generation);
    },
  };
}
