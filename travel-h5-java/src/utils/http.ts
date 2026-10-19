export class ApiError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
const ROOT = (import.meta.env?.VITE_API_ROOT || "/api").replace(/\/$/, "");
interface Envelope<T> {
  success: boolean;
  data: T;
  code: number;
  errorCode?: string;
  message?: string;
}
interface Token {
  token: string;
  headerName: string;
}
let token: Promise<Token> | null = null;
let sessionGeneration = 0;
export function resetCsrf() {
  token = null;
}
export function resetHttpSession() {
  sessionGeneration++;
  resetCsrf();
}
export function messageOf(error: unknown): string {
  return error instanceof Error ? error.message : "请求失败，请稍后重试";
}
async function csrf(): Promise<Token> {
  if (token) return token;
  const pending = fetch(`${ROOT}/auth/csrf`, {
    credentials: "same-origin",
    signal: AbortSignal.timeout(10000),
  })
    .then(async (response) => {
      if (!response.ok)
        throw new ApiError(
          response.status,
          "CSRF_UNAVAILABLE",
          "无法取得请求凭证，请稍后重试",
        );
      return ((await response.json()) as Envelope<Token>).data;
    })
    .catch((error) => {
      if (token === pending) token = null;
      throw error;
    });
  token = pending;
  return pending;
}
export async function apiFetch(
  url: string | URL | Request,
  options: RequestInit = {},
  retry = true,
): Promise<Response> {
  const session = sessionGeneration;
  const method = (options.method || "GET").toUpperCase();
  const headers = new Headers(options.headers);
  if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
    const value = await csrf();
    if (session !== sessionGeneration)
      throw new ApiError(409, "SESSION_CHANGED", "登录状态已变化，请在当前账号下重新操作");
    headers.set(value.headerName, value.token);
  }
  const response = await fetch(url, {
    ...options,
    headers,
    credentials: "same-origin",
  });
  if (response.status === 403 && retry) {
    const body = (await response
      .clone()
      .json()
      .catch(() => null)) as { errorCode?: string } | null;
    if (body?.errorCode === "CSRF_INVALID" && session === sessionGeneration) {
      resetCsrf();
      return apiFetch(url, options, false);
    }
  }
  if (
    response.status === 401 &&
    session === sessionGeneration &&
    !String(url).endsWith("/auth/login") &&
    typeof window !== "undefined"
  )
    window.dispatchEvent(new Event("auth-expired"));
  return response;
}
export async function request<T>(
  url: string,
  method = "GET",
  data?: unknown,
  timeoutMs = 130000,
  signal?: AbortSignal,
): Promise<T> {
  const controller = new AbortController(),
    timeout = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await apiFetch(url, {
      method,
      signal: signal ? AbortSignal.any([signal, controller.signal]) : controller.signal,
      headers: { "Content-Type": "application/json" },
      ...(data === undefined ? {} : { body: JSON.stringify(data) }),
    });
    const body = (await response
      .json()
      .catch(() => null)) as Envelope<T> | null;
    if (controller.signal.aborted)
      throw new ApiError(408, "REQUEST_TIMEOUT", "请求超时，请稍后重试");
    if (!response.ok || !body?.success)
      throw new ApiError(
        response.status,
        body?.errorCode || "HTTP_ERROR",
        body?.message || `请求失败：HTTP ${response.status}`,
      );
    return body.data;
  } catch (error) {
    if (signal?.aborted) throw new ApiError(499, "REQUEST_CANCELLED", "请求已取消");
    if (error instanceof ApiError) throw error;
    if (controller.signal.aborted)
      throw new ApiError(408, "REQUEST_TIMEOUT", "请求超时，请稍后重试");
    throw new ApiError(0, "NETWORK_ERROR", "连接失败，请检查服务后重试");
  } finally {
    clearTimeout(timeout);
  }
}
export function api<T>(path: string, method = "GET", data?: unknown, signal?: AbortSignal) {
  return request<T>(`${ROOT}${path}`, method, data, 130000, signal);
}
