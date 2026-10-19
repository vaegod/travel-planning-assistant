import { api } from "./http";
import type {
  Conversation,
  MessagePage,
  Page,
  TravelPlan,
  Trip,
  TripDetail,
} from "../types";
export const recommendTrip = (city: string, days: number, budget: number,
  options?: {requestId: string; signal: AbortSignal}) =>
  api<TravelPlan>("/travel/recommend", "POST", { city, days, budget, requestId: options?.requestId }, options?.signal);
export const cancelRecommendedTrip = (requestId: string) =>
  api<{cancelled: boolean}>(`/travel/recommend/${requestId}`, "DELETE");
export const listTrips = (page = 1) =>
  api<Page<Trip>>(`/trips?page=${page}&pageSize=10`);
export const getTrip = (id: string, version?: number) =>
  api<TripDetail>(
    `/trips/${encodeURIComponent(id)}${version ? `?version=${version}` : ""}`,
  );
export const saveTrip = (plan: TravelPlan, title?: string) =>
  api<TripDetail>("/trips", "POST", { plan, title });
export const deleteTrip = (id: string) =>
  api<void>(`/trips/${encodeURIComponent(id)}`, "DELETE");
export const listConversations = (page = 1) =>
  api<Page<Conversation>>(`/conversations?page=${page}&pageSize=10`);
export const getConversation = (id: string) =>
  api<Conversation>(`/conversations/${encodeURIComponent(id)}`);
export const newConversation = (tripId?: string) =>
  api<Conversation>("/conversations", "POST", tripId ? { tripId } : {});
export const deleteConversation = (id: string) =>
  api<void>(`/conversations/${encodeURIComponent(id)}`, "DELETE");
export const getMessages = (id: string, before?: number) =>
  api<MessagePage>(
    `/conversations/${encodeURIComponent(id)}/messages?pageSize=20${before ? `&before=${before}` : ""}`,
  );
