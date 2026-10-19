import type { SavedMessage, UiMessage } from "../types";
export function mergeMessages(
  previous: UiMessage[],
  saved: SavedMessage[],
  activeRequestId?: string,
): UiMessage[] {
  const values = new Map(
    previous.map((message) => [
      `${message.requestId}:${message.role}`,
      message,
    ]),
  );
  for (const item of saved) {
    const role = item.role === "assistant" ? "ai" : "user";
    const key = `${item.requestId}:${role}`;
    const live = values.get(key);
    if (role === "ai" && item.requestId === activeRequestId && live) {
      // Streaming callbacks still own this object; a history snapshot must not replace it.
      live.seq = item.seq;
      live.timestamp = item.createdAt;
      continue;
    }
    values.set(key, {
      id: item.id,
      seq: item.seq,
      requestId: item.requestId,
      role,
      content: item.content,
      evidence: item.evidence,
      status: item.status,
      statusText:
        item.status === "stopped"
          ? "已停止生成"
          : item.status === "error"
            ? item.errorMessage || "生成失败，请重试"
            : item.status === "streaming"
              ? "正在生成，内容可能尚未完整保存"
              : "",
      question: previous.find(
        (m) => m.requestId === item.requestId && m.role === "user",
      )?.content,
      timestamp: item.createdAt,
    });
  }
  // Local rows have no server sequence yet. Keep their place before the next
  // known row instead of moving failed requests behind every later saved turn.
  const localOrder = new Map<string, { nextSeq: number; index: number }>();
  let nextSeq = Infinity;
  for (let index = previous.length - 1; index >= 0; index--) {
    const item = previous[index];
    const key = `${item.requestId}:${item.role}`;
    const current = values.get(key)!;
    if (current.seq < Number.MAX_SAFE_INTEGER - 1) nextSeq = current.seq;
    else localOrder.set(key, { nextSeq, index });
  }
  const result = [...values.values()].sort((a, b) => {
    const first = localOrder.get(`${a.requestId}:${a.role}`);
    const second = localOrder.get(`${b.requestId}:${b.role}`);
    const firstSeq = first?.nextSeq ?? a.seq;
    const secondSeq = second?.nextSeq ?? b.seq;
    if (firstSeq !== secondSeq) return firstSeq < secondSeq ? -1 : 1;
    if (first && second) return first.index - second.index;
    return first ? -1 : second ? 1 : 0;
  });
  for (const message of result)
    if (message.role === "ai")
      message.question = result.find(
        (m) => m.requestId === message.requestId && m.role === "user",
      )?.content;
  return result;
}
