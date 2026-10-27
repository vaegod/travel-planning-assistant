export interface User {
  id: number;
  username: string;
}
export interface Slot {
  spot: string;
  duration?: string;
  ticket?: string;
  transportation?: string;
  description?: string;
  placeId?: string;
  latitude?: number;
  longitude?: number;
  indoor?: boolean;
  rest?: boolean;
  sourceUrl?: string;
  queriedAt?: string;
  visitMinutes?: number;
  travelMinutes?: number;
  estimatedCost?: number;
  route?: {
    fromId: string;
    toId: string;
    meters: number;
    seconds: number;
    profile: string;
    source: string;
    queriedAt: string;
    estimatedFare: number | null;
  };
}
export interface Day {
  day: number;
  date?: string;
  morning: Slot;
  afternoon: Slot;
  evening: Slot;
}
export interface TravelPlan {
  success: boolean;
  city: string;
  days: number;
  travelMode?: import("./utils/travelModes").TravelMode | null;
  totalBudget: number;
  dailyItinerary: Day[];
  budgetBreakdown?: Record<string, number>;
  tips?: string[];
  warnings?: string[];
  estimatedTotal?: number;
  remainingBudget?: number;
  evidence?: {
    status: string;
    attribution: string;
    priceNotice: string;
    traces: ToolTrace[];
  };
  agentEvidence?: {
    status: string;
    budgetScope: string;
    validation: AgentValidation;
    traces: ToolTrace[];
    visitRequirements?: VisitRequirement[];
    ticketRequirements?: TicketRequirement[];
  };
}
export interface VisitRequirement { placeName: string; placeId?: string | null; minimumMinutes: number }
export interface TicketRequirement { placeName: string; placeId?: string | null; minimumBudget: number; source: string }
export interface AgentValidation {
  valid: boolean;
  issues: string[];
  warnings: string[];
  estimatedTotal?: number;
  remainingBudget?: number;
}
export interface AgentJob extends PlanningJob {
  visitRequirements?: VisitRequirement[];
  ticketRequirements?: TicketRequirement[];
  recoveryAttempts?: number;
  recoveryDurationMs?: number;
  refusal?: { code: string; reason: string; minimumExpenses?: {
    policy: string; accommodation: number; food: number; other: number; total: number; requestedBudget: number;
  } | null } | null;
  travelMode: import("./utils/travelModes").TravelMode;
  validation?: AgentValidation;
  modelRounds: number;
  toolCalls: number;
}
export interface Trip {
  id: string;
  title: string;
  city: string;
  days: number;
  budget: number;
  currentVersion: number;
  createdAt: string;
  updatedAt: string;
}
export interface TripDetail {
  trip: Trip;
  plan: TravelPlan;
  versions: { version: number; createdAt: string }[];
}
export interface Conversation {
  id: string;
  tripId: string | null;
  title: string;
  context: { city?: string; days?: number; budget?: number };
  activeRequestId: string | null;
  createdAt: string;
  updatedAt: string;
}
export type MessageStatus = "streaming" | "completed" | "stopped" | "error";
export interface SavedMessage {
  evidence?: import('./utils/knowledge').ChatEvidence | null;
  id: string;
  seq: number;
  requestId: string;
  role: "user" | "assistant";
  content: string;
  status: MessageStatus;
  errorCode?: string;
  errorMessage?: string;
  createdAt: string;
}
export interface UiMessage {
  evidence?: import('./utils/knowledge').ChatEvidence | null;
  id: string;
  seq: number;
  requestId: string;
  role: "user" | "ai";
  content: string;
  status: MessageStatus;
  statusText: string;
  question?: string;
  timestamp: string;
}
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  pageSize: number;
}
export interface MessagePage {
  items: SavedMessage[];
  nextBefore: number | null;
  hasMore: boolean;
}

export interface ToolTrace {
  tool: string;
  status: string;
  durationMs: number;
  detail: string;
  at: string;
}
export interface PlanChange {
  path: string;
  label: string;
  before: string;
  after: string;
}
export interface PlanningJob {
  id: string;
  status: "QUEUED" | "RUNNING" | "READY" | "SAVED" | "ERROR" | "CANCELLED" | "REFUSED";
  progress: string;
  errorCode?: string;
  message?: string;
  tripId?: string;
  baseVersion?: number;
  plan?: TravelPlan;
  changes: PlanChange[];
  traces: ToolTrace[];
  savedVersion?: number;
}
export type RevisionAction = "indoor" | "relaxed" | "budget" | "refresh";
