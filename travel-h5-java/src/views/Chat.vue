<template>
  <div class="page-container chat-page">
    <div class="page-header">
      <van-nav-bar
        title="旅游规划智能助手"
        left-arrow
        left-text="返回"
        @click-left="router.back()"
      />
    </div>
    <div class="chat-tools">
      <van-button size="mini" plain @click="router.push('/chat')"
        >新对话</van-button
      ><van-button size="mini" plain @click="router.push('/conversations')"
        >历史会话</van-button
      ><van-button
        v-if="conversation?.tripId"
        size="mini"
        plain
        @click="
          router.push({
            path: '/detail',
            query: { tripId: conversation.tripId },
          })
        "
        >查看行程</van-button
      ><span>{{ conversation?.title || "新的旅行话题" }}</span>
    </div>
    <div v-if="conversation?.tripId" class="context-strip">
      对话中的建议不会改动已保存行程；需要重新规划时可回首页生成新行程。
    </div>
    <div
      v-if="
        conversation?.context.city ||
        conversation?.context.budget ||
        conversation?.context.days
      "
      class="context-strip"
    >
      {{ conversation?.context.city || "目的地待定" }}
      <span v-if="conversation?.context.days"
        >· {{ conversation.context.days }} 天</span
      >
      <span v-if="conversation?.context.budget"
        >· 预算 ¥{{ conversation.context.budget }}</span
      >
    </div>
    <div
      v-if="historyError"
      class="account-error chat-history-error"
      role="alert"
    >
      {{ historyError }} <button @click="loadRoute">重试</button>
    </div>
    <div v-if="remoteBusy || pendingStop" class="context-strip" role="status">
      {{ pendingStop?.error || (stopping ? "正在确认停止，请稍候…" : "此会话仍有一条待停止或待保存的回复。") }}
      <van-button size="mini" :loading="stopping" :disabled="stopping" @click="stopRemote"
        >{{ pendingStop ? "重试停止" : "停止并恢复输入" }}</van-button
      >
    </div>
    <div class="chat-container" ref="chatContainer">
      <div v-if="loadingHistory" class="account-loading">
        <van-loading>正在恢复会话...</van-loading>
      </div>
      <van-button
        v-if="hasMore"
        size="small"
        block
        plain
        :loading="loadingOlder"
        @click="loadOlder"
        >加载更早的消息</van-button
      >
      <div v-if="!loadingHistory && !messages.length" class="chat-empty">
        <div class="chat-welcome"><span><van-icon name="chat-o" /></span><h1>旅途中的问题，尽管问。</h1><p>聊聊景点、美食和旅行灵感，一起把计划想得更周全。</p></div>
        <div class="quick-questions">
          <div class="quick-title">常见问题</div>
          <button
            v-for="q in quickQuestions"
            :key="q"
            type="button"
            class="quick-tag"
            :disabled="sendBlocked"
            @click="sendQuestion(q)"
            >{{ q }}</button
          >
        </div>
      </div>
      <div v-else class="message-list">
        <div v-for="msg in messages" :key="msg.id" class="message-entry">
          <ChatBubble :message="msg" />
          <div v-if="msg.role === 'ai' && msg.evidence?.mode === 'KNOWLEDGE'" class="historical-evidence"><small>历史资料引用（存档）</small><MessageEvidence :evidence="msg.evidence" /></div>
          <div v-if="msg.statusText" class="message-status" role="status">
            {{ msg.statusText }}
          </div>
          <van-button
            v-if="
              msg.status === 'error' &&
              !sendBlocked &&
              msg.question
            "
            size="mini"
            class="retry-button"
            @click="sendQuestion(msg.question)"
            >重试此问题</van-button
          >
        </div>
        <div v-if="isStreaming" class="streaming-indicator" role="status">
          <van-loading type="spinner" size="20px" /><span
            >正在准备回答...</span
          >
        </div>
      </div>
    </div>
    <div class="chat-input-area">
      <van-field
        v-model="inputMessage"
        placeholder="输入您的问题..."
        maxlength="2000"
        :disabled="sendBlocked"
        @keyup.enter="sendMessage"
        ><template #button
          ><van-button
            v-if="isStreaming"
            type="danger"
            size="small"
            @click="stopGeneration"
            >停止生成</van-button
          ><van-button
            v-else-if="stopping"
            type="primary"
            size="small"
            loading
            disabled
            >停止中…</van-button
          ><van-button
            v-else
            type="primary"
            size="small"
            :disabled="!inputMessage.trim() || sendBlocked"
            @click="sendMessage"
            >发送</van-button
          ></template
        ></van-field
      >
    </div>
  </div>
</template>
<script setup lang="ts">
import {
  ref,
  reactive,
  computed,
  nextTick,
  onMounted,
  onBeforeUnmount,
  watch,
} from "vue";
import { useRouter, useRoute } from "vue-router";
import { showToast } from "vant";
import { startChat } from "../utils/request";
import { getConversation, getMessages, newConversation } from "../utils/data";
import { api, messageOf } from "../utils/http";
import { mergeMessages } from "../utils/messageState";
import type { Conversation, UiMessage } from "../types";
import type { ChatHandle } from "../utils/chatStream";
import ChatBubble from "../components/ChatBubble.vue";
import MessageEvidence from '../components/MessageEvidence.vue';
const router = useRouter(),
  route = useRoute();
const chatContainer = ref<HTMLElement | null>(null),
  inputMessage = ref(""),
  messages = ref<UiMessage[]>([]),
  isStreaming = ref(false);
const conversationId = ref<string | null>(null),
  conversation = ref<Conversation | null>(null),
  loadingHistory = ref(false),
  loadingOlder = ref(false),
  historyError = ref(""),
  hasMore = ref(false),
  nextBefore = ref<number | null>(null);
type StopTarget = { conversationId: string; requestId: string; error: string };
const pendingStop = ref<StopTarget | null>(null), stopping = ref(false);
const remoteBusy = computed(
  () => !!conversation.value?.activeRequestId && !isStreaming.value,
);
const sendBlocked = computed(
  () => isStreaming.value || remoteBusy.value || loadingHistory.value || stopping.value || !!pendingStop.value,
);
const quickQuestions = [
  "北京有哪些必去的景点？",
  "上海美食推荐",
  "成都三日游攻略",
  "如何选择旅行保险？",
];
let activeRequest: ChatHandle | null = null,
  activeReply: UiMessage | null = null,
  disposed = false,
  generation = 0,
  loadEpoch = 0,
  historyInitialized = false;
const scrollToBottom = async () => {
  await nextTick();
  if (chatContainer.value)
    chatContainer.value.scrollTop = chatContainer.value.scrollHeight;
};
const reload = async (id: string) => {
  const epoch = loadEpoch;
  const turn = generation;
  const [record, page] = await Promise.all([
    getConversation(id),
    getMessages(id),
  ]);
  if (disposed || conversationId.value !== id || epoch !== loadEpoch || turn !== generation) return false;
  conversation.value = record;
  messages.value = mergeMessages(messages.value, page.items, activeRequest?.requestId);
  if (!historyInitialized) {
    nextBefore.value = page.nextBefore;
    hasMore.value = page.hasMore;
    historyInitialized = true;
  }
  return true;
};
const loadOlder = async () => {
  const epoch = loadEpoch;
  const id = conversationId.value;
  if (!id || nextBefore.value === null) return;
  loadingOlder.value = true;
  try {
    const page = await getMessages(id, nextBefore.value);
    if (!disposed && epoch === loadEpoch && conversationId.value === id) {
      messages.value = mergeMessages(messages.value, page.items, activeRequest?.requestId);
      nextBefore.value = page.nextBefore;
      hasMore.value = page.hasMore;
    }
  } catch (e) {
    if (!disposed && epoch === loadEpoch) showToast(messageOf(e));
  } finally {
    if (!disposed && epoch === loadEpoch) loadingOlder.value = false;
  }
};
const confirmStop = async (target: StopTarget, cancel: () => Promise<boolean>) => {
  const epoch = loadEpoch;
  const current = () => !disposed && pendingStop.value === target
    && conversationId.value === target.conversationId && loadEpoch === epoch;
  stopping.value = true;
  try {
    if (!await cancel()) throw new Error("未能确认服务端停止，请重试停止");
    if (!current()) return;
    const loaded = await reload(target.conversationId);
    if (!current()) return;
    if (!loaded || conversation.value?.activeRequestId)
      throw new Error("尚未确认会话已空闲，请重试停止");
    pendingStop.value = null;
    stopping.value = false;
  } catch (error) {
    if (current()) target.error = messageOf(error);
  } finally {
    if (current()) stopping.value = false;
  }
};
const stopGeneration = () => {
  if (pendingStop.value && !activeRequest) return;
  generation++;
  const handle = activeRequest,
    reply = activeReply,
    id = conversationId.value;
  activeRequest = null;
  activeReply = null;
  isStreaming.value = false;
  if (reply) {
    reply.status = "stopped";
    reply.statusText = handle ? "已停止接收，等待服务端确认" : "已停止生成";
  }
  if (!handle) return;
  if (disposed || !id) { void handle.cancel().catch(() => false); return; }
  pendingStop.value = { conversationId: id, requestId: handle.requestId, error: "" };
  void confirmStop(pendingStop.value, () => handle.cancel());
};
const loadRoute = async () => {
  const id = typeof route.params.id === "string" ? route.params.id : null;
  if (id === conversationId.value && !historyError.value) return;
  stopGeneration();
  const epoch = ++loadEpoch;
  pendingStop.value = null;
  stopping.value = false;
  conversationId.value = id;
  conversation.value = null;
  messages.value = [];
  hasMore.value = false;
  nextBefore.value = null;
  historyInitialized = false;
  historyError.value = "";
  loadingHistory.value = false;
  loadingOlder.value = false;
  if (!id) return;
  loadingHistory.value = true;
  try {
    await reload(id);
    if (epoch === loadEpoch) void scrollToBottom();
  } catch (e) {
    if (epoch === loadEpoch) historyError.value = messageOf(e);
  } finally {
    if (epoch === loadEpoch) loadingHistory.value = false;
  }
};
const stopRemote = async () => {
  if (disposed || stopping.value) return;
  const record = conversation.value;
  const id = record?.id || pendingStop.value?.conversationId;
  const requestId = record?.activeRequestId || pendingStop.value?.requestId;
  if (!id || !requestId) return;
  pendingStop.value = { conversationId: id, requestId, error: "" };
  await confirmStop(pendingStop.value, async () =>
    (await api<{cancelled:boolean}>(`/travel/chat/${requestId}`, "DELETE")).cancelled);
};
const sendMessage = () => sendQuestion(inputMessage.value);
const sendQuestion = async (question: string) => {
  const text = question.trim();
  if (!text || sendBlocked.value)
    return;
  if (text.length > 2000) {
    showToast("消息不能超过2000个字符");
    return;
  }
  const epoch = ++generation;
  isStreaming.value = true;
  historyError.value = "";
  try {
    if (!conversationId.value) {
      const created = await newConversation();
      if (disposed || epoch !== generation) return;
      conversationId.value = created.id;
      conversation.value = created;
      await router.replace(`/chat/${created.id}`);
    }
    const id = conversationId.value;
    if (!id || disposed || epoch !== generation) return;
    const requestId = crypto.randomUUID();
    inputMessage.value = "";
    messages.value.push({
      id: crypto.randomUUID(),
      seq: Number.MAX_SAFE_INTEGER - 1,
      requestId,
      role: "user",
      content: text,
      status: "completed",
      statusText: "",
      timestamp: new Date().toISOString(),
    });
    const reply = reactive<UiMessage>({
      id: crypto.randomUUID(),
      seq: Number.MAX_SAFE_INTEGER,
      requestId,
      role: "ai",
      content: "",
      status: "streaming",
      statusText: "",
      question: text,
      timestamp: new Date().toISOString(),
    });
    messages.value.push(reply);
    activeReply = reply;
    const handle = startChat(
      id,
      text,
      (chunk) => {
        if (activeRequest !== handle || disposed) return;
        reply.content += chunk;
        void scrollToBottom();
      },
      requestId,
    );
    activeRequest = handle;
    void handle.finished
      .then((outcome) => {
        if (activeRequest !== handle || disposed) return;
        reply.status = outcome.status === "done" ? "completed" : "stopped";
        reply.statusText = outcome.status === "done" ? "" : "已停止生成";
      })
      .catch((error) => {
        if (activeRequest !== handle || disposed) return;
        reply.status = "error";
        reply.statusText = messageOf(error);
        showToast(reply.statusText);
      })
      .finally(async () => {
        if (activeRequest !== handle || disposed) return;
        const settled = ++generation;
        activeRequest = null;
        activeReply = null;
        isStreaming.value = false;
        try {
          await reload(id);
        } catch (e) {
          if (!disposed && settled === generation) historyError.value = messageOf(e);
        }
        void scrollToBottom();
      });
    void scrollToBottom();
  } catch (e) {
    if (!disposed && epoch === generation) {
      isStreaming.value = false;
      historyError.value = messageOf(e);
    }
  }
};
onMounted(() => {
  void loadRoute();
  window.addEventListener("pagehide", stopGeneration);
});
watch(
  () => route.params.id,
  () => {
    void loadRoute();
  },
);
onBeforeUnmount(() => {
  disposed = true;
  stopGeneration();
  window.removeEventListener("pagehide", stopGeneration);
});
</script>
<style scoped>
.page-header {
  min-height: 56px; flex-shrink: 0;
}
.chat-page {
  display: flex;
  flex-direction: column;
  height: calc(100dvh - 76px);
  min-height: 0;
  max-width: 960px;
  margin: auto;
  padding-bottom: 0;
}

.chat-container {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 24px 32px;
  overscroll-behavior: contain;
}

.chat-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 100%;
}

.quick-questions {
  margin-top: 10px; max-width: 520px;
  text-align: center;
}

.quick-title {
  font-size: 14px;
  color: #999;
  margin-bottom: 16px;
}

.quick-tag {
  margin: 5px;
  cursor: pointer;
  padding: 9px 13px;
  background: white;
  color: #617b9a;
  border: 1px solid var(--line);
  border-radius: 9px;
}

.message-list {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.streaming-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 16px;
  color: #999;
  font-size: 14px;
}

.chat-input-area {
  flex-shrink: 0;
  background: white;
  padding: 16px 24px;
  border: 1px solid var(--line);
  border-radius: 16px 16px 0 0;
}
.chat-input-area :deep(.van-field) { background: #f4f7fb; border-radius: 12px; padding: 8px 12px; }
.chat-input-area :deep(.van-field__control) { min-height: 30px; }
.message-entry {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.message-status {
  font-size: 12px;
  color: #666;
  padding: 0 8px;
}
.retry-button {
  align-self: flex-start;
}
.chat-tools {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  flex-shrink: 0;
  gap: 8px;
  padding: 12px 24px;
  background: #fff;
  font-size: 12px;
}
.context-strip {
  padding: 9px 24px;
  flex-shrink: 0;
  background: #edf5fb;
  color: #526e83;
  font-size: 12px;
  line-height: 1.7;
}
.chat-history-error {
  margin: 8px 16px;
}
.chat-tools > span { flex: 1; min-width: 100px; overflow-wrap: anywhere; color: #8392a3; text-align: right; }
.chat-tools .van-button { font-size: 12px; padding: 0 12px; border-color: var(--line); color: #66809e; background: #f8fafd; }
.chat-page .page-header { position: static; }
.historical-evidence > small { display:block; color:var(--muted); font-size:11px; margin:6px 0; }
.chat-welcome { text-align: center; margin-bottom: 24px; }
.chat-welcome > span { display: grid; place-items: center; width: 68px; height: 68px; margin: 0 auto 20px; font-size: 30px; color: var(--primary); background: #e7effd; border: 1px solid #dce7fa; border-radius: 22px; }
.chat-welcome h1 { font-size: 24px; letter-spacing: -.5px; margin: 0 0 10px; }
.chat-welcome p { color: var(--muted); font-size: 12px; margin: 0; line-height: 1.8; }
@media (max-width: 899px) {
  .chat-page { height: 100dvh; padding-bottom: calc(58px + env(safe-area-inset-bottom)); }
}
@media (max-width: 599px) {
  .chat-container { padding: 18px 16px; }
  .chat-tools { padding: 10px 16px; }
  .chat-tools > span { flex-basis: 100%; text-align: left; font-size: 11px; }
  .chat-input-area { padding: 12px 16px; border-radius: 0; border-left: 0; border-right: 0; }
  .context-strip { padding: 8px 16px; }
  .quick-questions { margin-top: 0; }
  .quick-tag { font-size: 12px; margin: 4px; }
  .chat-welcome { margin-bottom: 18px; }
  .chat-welcome h1 { font-size: 19px; }
  .chat-welcome > span { width: 54px; height: 54px; font-size: 25px; border-radius: 18px; margin-bottom: 16px; }
}
</style>
