import { api } from "./http";
import type { PlanningJob, TripDetail, RevisionAction } from "../types";
export const startPlanning = (city: string, days: number, budget: number) =>
  api<PlanningJob>("/planning/jobs", "POST", { city, days, budget });
export const getPlanning = (id: string) =>
  api<PlanningJob>(`/planning/jobs/${encodeURIComponent(id)}`);
export const cancelPlanning = (id: string) =>
  api<{ cancelled: boolean }>(
    `/planning/jobs/${encodeURIComponent(id)}`,
    "DELETE",
  );
export const savePlanning = (id: string) =>
  api<TripDetail>(`/planning/jobs/${encodeURIComponent(id)}/save`, "POST", {});
export const revisePlanning = (
  id: string,
  baseVersion: number,
  action: RevisionAction,
  day: number,
  budget: number,
  instruction: string,
) =>
  api<PlanningJob>(
    `/planning/trips/${encodeURIComponent(id)}/revisions`,
    "POST",
    { baseVersion, action, day, budget, instruction },
  );
