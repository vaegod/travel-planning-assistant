import { createSseParser } from "./sse.js";
import { validChatEvidence } from './chatEvidence.js';
import type { ChatEvidence } from './knowledge';

interface StreamEvent {
  type: string;
  requestId: string;
  code?: string;
  message?: string;
  content?: string;
  evidence?: ChatEvidence;
}
interface ChatOptions {
  baseUrl: string;
  conversationId: string;
  message: string;
  onChunk: (chunk: string) => void;
  fetchImpl?: typeof fetch;
  requestId?: string;
  timeoutMs?: number;
  city?: string;
  onEvidence?: (value:ChatEvidence)=>void;
}
export interface ChatHandle {
  requestId: string;
  finished: Promise<{ status: "done" | "cancelled" }>;
  cancel: () => Promise<boolean>;
}
export class ChatError extends Error {
  code: string;
  constructor(code: string, message: string) {
    super(message);
    this.code = code;
  }
}

export async function readChatResponse(
  response: Response,
  requestId: string,
  onChunk: (chunk: string) => void,
  signal?: AbortSignal,
  onEvidence?: (value:ChatEvidence)=>void,
) {
  if (!response.ok) {
    let body: { errorCode?: string; message?: string } | undefined;
    try {
      body = await response.json();
    } catch {
      /* An intermediary may return HTML. */
    }
    throw new ChatError(
      body?.errorCode || "HTTP_ERROR",
      body?.message || `请求失败：HTTP ${response.status}`,
    );
  }
  if (
    !response.body ||
    !response.headers.get("content-type")?.includes("text/event-stream")
  ) {
    throw new ChatError("STREAM_PROTOCOL_ERROR", "服务未返回流式内容");
  }
  let started = false;
  let done = false;
  let evidenceReceived = false;
  let hasContent = false;
  const parser = createSseParser((event: StreamEvent) => {
    if (done) return;
    if (event.requestId !== requestId)
      throw new ChatError("STREAM_PROTOCOL_ERROR", "响应与当前请求不匹配");
    if (event.type === "error")
      throw new ChatError(
        event.code || "MODEL_ERROR",
        event.message || "AI 回复失败",
      );
    if (event.type === "start" && !started) {
      started = true;
      return;
    }
    if (
      event.type === 'evidence' && started && !hasContent && !evidenceReceived && validChatEvidence(event.evidence)
    ) {
      evidenceReceived = true;
      onEvidence?.(event.evidence!);
      return;
    }
    if (
      event.type === "chunk" &&
      started &&
      typeof event.content === "string"
    ) {
      hasContent = true;
      onChunk(event.content);
      return;
    }
    if (event.type === "done" && started) {
      done = true;
      return;
    }
    throw new ChatError("STREAM_PROTOCOL_ERROR", "流式事件格式或顺序不正确");
  });
  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8", { fatal: true });
  try {
    while (!done) {
      if (signal?.aborted) throw new DOMException("Aborted", "AbortError");
      const part = await reader.read();
      if (part.done) {
        parser.push(decoder.decode());
        parser.finish();
        if (!done)
          throw new ChatError(
            "STREAM_INTERRUPTED",
            "连接提前结束，回答可能不完整，请重试",
          );
        break;
      }
      parser.push(decoder.decode(part.value, { stream: true }));
    }
  } catch (error) {
    if (
      error instanceof ChatError ||
      (error instanceof Error && error.name === "AbortError")
    )
      throw error;
    throw new ChatError("STREAM_PROTOCOL_ERROR", "流式数据解析失败，请重试");
  } finally {
    try {
      await reader.cancel();
    } catch {
      /* The network may already be closed. */
    }
    reader.releaseLock();
  }
}

// Returns synchronously so callers can stop before the first model token arrives.
export function createChatRequest({
  baseUrl,
  conversationId,
  message,
  onChunk,
  fetchImpl = globalThis.fetch,
  requestId = globalThis.crypto.randomUUID(),
  timeoutMs = 185000,
  city,
  onEvidence,
}: ChatOptions): ChatHandle {
  const controller = new AbortController();
  let cancelled = false;
  let timedOut = false;
  let terminal = false;
  let notification: Promise<boolean> | undefined;
  const notifyCancel = () => {
    notification ??= fetchImpl(
      `${baseUrl}/chat/${encodeURIComponent(requestId)}`,
      {
        method: "DELETE",
        keepalive: true,
        signal: AbortSignal.timeout(10000),
      },
    )
      .then(async (response) => {
        if (!response.ok) return false;
        const body = await response.json();
        return body?.success === true && body?.data?.cancelled === true;
      })
      .catch(() => false);
    return notification;
  };
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
    void notifyCancel();
  }, timeoutMs);
  const finished: ChatHandle["finished"] = (async () => {
    try {
      const response = await fetchImpl(`${baseUrl}/chat`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ requestId, conversationId, message, city }),
        signal: controller.signal,
      });
      await readChatResponse(
        response,
        requestId,
        (content) => {
          if (!cancelled && !timedOut) onChunk(content);
        },
        controller.signal,
        value => { if (!cancelled && !timedOut) onEvidence?.(value); },
      );
      if (cancelled) return { status: "cancelled" };
      if (timedOut) throw new ChatError("CLIENT_TIMEOUT", "生成超时，请重试");
      return { status: "done" };
    } catch (error) {
      if (cancelled) return { status: "cancelled" };
      if (timedOut) throw new ChatError("CLIENT_TIMEOUT", "生成超时，请重试");
      if (error instanceof ChatError) throw error;
      throw new ChatError("NETWORK_ERROR", "连接失败，请检查网络后重试");
    } finally {
      terminal = true;
      clearTimeout(timer);
    }
  })();
  return {
    requestId,
    finished,
    cancel() {
      if (terminal && !cancelled) return Promise.resolve(true);
      cancelled = true;
      controller.abort();
      clearTimeout(timer);
      return notifyCancel();
    },
  };
}
