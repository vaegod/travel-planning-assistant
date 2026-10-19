<template>
  <main class="account-page">
    <van-nav-bar
      title="历史会话"
      left-arrow
      left-text="返回"
      @click-left="router.push('/profile')"
    />
    <div class="account-content">
      <div class="account-eyebrow">聊过的灵感</div>
      <h1 class="account-heading">接着上次的话题。</h1>
      <p class="account-description">
        共 {{ total }} 个会话。每个话题独立保存，继续提问时保留自己的旅行要求。
      </p>
      <van-button type="primary" block round @click="router.push('/chat')"
        >开始新对话</van-button
      >
      <p v-if="error" class="account-error" role="alert">
        {{ error }} <button @click="load">重试</button>
      </p>
      <div v-if="loading" class="account-loading">
        <van-loading>正在读取会话...</van-loading>
      </div>
      <van-empty
        v-else-if="!items.length && !error"
        description="还没有聊天记录"
      />
      <article
        v-for="conversation in items"
        :key="conversation.id"
        class="archive-card"
      >
        <h2>{{ conversation.title }}</h2>
        <div class="archive-meta">
          <span v-if="conversation.context.city">{{
            conversation.context.city
          }}</span
          ><span v-if="conversation.context.days"
            >{{ conversation.context.days }} 天</span
          ><span v-if="conversation.context.budget"
            >预算 ¥{{ conversation.context.budget }}</span
          ><van-tag v-if="conversation.activeRequestId" type="primary" plain
            >生成中</van-tag
          >
        </div>
        <div class="archive-actions">
          <span class="archive-date">{{
            new Date(conversation.updatedAt).toLocaleDateString("zh-CN")
          }}</span>
          <div>
            <van-button size="small" plain @click="remove(conversation)"
              >删除</van-button
            >
            <van-button
              type="primary"
              size="small"
              @click="router.push(`/chat/${conversation.id}`)"
              >继续对话</van-button
            >
          </div>
        </div>
      </article>
      <van-pagination
        v-if="total > 10"
        v-model="page"
        :total-items="total"
        :items-per-page="10"
        :show-page-size="3"
        class="account-pagination"
        @change="load"
      />
    </div>
  </main>
</template>
<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from "vue";
import { useRouter } from "vue-router";
import { showConfirmDialog, showToast } from "vant";
import { listConversations, deleteConversation } from "../utils/data";
import { messageOf } from "../utils/http";
import type { Conversation } from "../types";
const router = useRouter(),
  items = ref<Conversation[]>([]),
  page = ref(1),
  total = ref(0),
  loading = ref(false),
  error = ref("");
let loadGeneration = 0, disposed = false;
const load = async () => {
  if (disposed) return;
  const current = ++loadGeneration, requestedPage = page.value;
  loading.value = true;
  error.value = "";
  try {
    const result = await listConversations(requestedPage);
    if (disposed || current !== loadGeneration) return;
    items.value = result.items;
    total.value = result.total;
    if (!items.value.length && page.value > 1) {
      page.value--;
      await load();
    }
  } catch (e) {
    if (!disposed && current === loadGeneration) error.value = messageOf(e);
  } finally {
    if (!disposed && current === loadGeneration) loading.value = false;
  }
};
const remove = async (conversation: Conversation) => {
  try {
    await showConfirmDialog({
      title: "删除会话",
      message: "会永久删除本会话的所有消息。正在生成的回复会先停止。",
    });
  } catch {
    return;
  }
  try {
    await deleteConversation(conversation.id);
    showToast("会话已删除");
    await load();
  } catch (e) {
    showToast(messageOf(e));
  }
};
onMounted(load);
onBeforeUnmount(() => { disposed = true; loadGeneration++; });
</script>
