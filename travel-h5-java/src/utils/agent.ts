import { api } from "./http";
import type { AgentJob, TripDetail, VisitRequirement, TicketRequirement } from "../types";
import type { TravelMode } from "./travelModes";
export interface AgentCapabilities { mapsConfigured: boolean; maxDays: number; draftRetentionMinutes: number; budgetScope: string; travelModes: TravelMode[] }
export const agentCapabilities = () => api<AgentCapabilities>("/agent/capabilities");
export const createAgent = (request: { requestId: string; city: string; days: number; budget: number; instruction: string; travelMode: TravelMode; visitRequirements?: VisitRequirement[]; ticketRequirements?: TicketRequirement[] }) =>
  api<AgentJob>("/agent/jobs", "POST", request);
export const reviseAgent = (tripId: string, request: { requestId: string; baseVersion: number; day: number; slot: string; instruction: string; visitRequirements?: VisitRequirement[]; ticketRequirements?: TicketRequirement[] }) =>
  api<AgentJob>(`/agent/trips/${encodeURIComponent(tripId)}/revisions`, "POST", request);
export const getAgent = (id: string) => api<AgentJob>(`/agent/jobs/${encodeURIComponent(id)}`);
export const cancelAgent = (id: string) => api<{ cancelled: boolean }>(`/agent/jobs/${encodeURIComponent(id)}`, "DELETE");
export const saveAgent = (id: string) => api<TripDetail>(`/agent/jobs/${encodeURIComponent(id)}/save`, "POST", {});
