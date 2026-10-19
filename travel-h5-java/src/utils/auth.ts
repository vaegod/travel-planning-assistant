import { reactive } from "vue";
import { api, ApiError, resetHttpSession } from "./http.ts";
import type { User } from "../types";
export const auth = reactive<{ user: User | null; ready: boolean }>({
  user: null,
  ready: false,
});
let pending: Promise<void> | null = null;
let generation = 0;
export async function ensureAuth() {
  if (auth.ready) return;
  if (pending) return pending;
  const current = generation;
  const request: Promise<void> = api<User>("/auth/me")
    .then((user) => {
      if (current !== generation) return;
      auth.user = user;
      auth.ready = true;
    })
    .catch((error) => {
      if (current !== generation) return;
      if (!(error instanceof ApiError) || error.status !== 401) throw error;
      auth.user = null;
      auth.ready = true;
    })
    .finally(() => {
      if (pending === request) pending = null;
    });
  pending = request;
  return request;
}
export async function login(username: string, password: string) {
  const current = ++generation;
  pending = null;
  const user = await api<User>("/auth/login", "POST", { username, password });
  if (current !== generation) return;
  auth.user = user;
  auth.ready = true;
  resetHttpSession();
}
export async function register(username: string, password: string) {
  await api<User>("/auth/register", "POST", { username, password });
  await login(username, password);
}
export async function logout() {
  await api("/auth/logout", "POST", {});
  clearAuth();
}
export async function updateUsername(
  username: string,
  currentPassword: string,
) {
  await api("/auth/username", "PATCH", { username, currentPassword });
  clearAuth();
}
export async function updatePassword(
  currentPassword: string,
  newPassword: string,
  confirmPassword: string,
) {
  await api("/auth/password", "PATCH", {
    currentPassword,
    newPassword,
    confirmPassword,
  });
  clearAuth();
}
export function clearAuth() {
  generation++;
  pending = null;
  auth.user = null;
  auth.ready = true;
  resetHttpSession();
}
export { safeRedirect } from "./redirect.ts";
