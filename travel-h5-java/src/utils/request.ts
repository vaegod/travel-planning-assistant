import { createChatRequest } from "./chatStream";
import { apiFetch, request } from "./http";
const BASE = (import.meta.env.VITE_API_BASE_URL || "/api/travel").replace(
  /\/$/,
  "",
);
export const post = <T>(url: string, data: unknown) =>
  request<T>(`${BASE}/${url.replace(/^\//, "")}`, "POST", data);
export const get = <T>(url: string) =>
  request<T>(`${BASE}/${url.replace(/^\//, "")}`);
export function startChat(
  conversationId: string,
  message: string,
  onChunk: (chunk: string) => void,
  requestId = crypto.randomUUID(),
  options: {city?:string} = {},
) {
  return createChatRequest({
    baseUrl: BASE,
    conversationId,
    message,
    onChunk,
    requestId,
    fetchImpl: apiFetch,
    ...options,
  });
}
