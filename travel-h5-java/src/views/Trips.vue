<template>
  <main class="account-page">
    <van-nav-bar
      title="我的行程"
      left-arrow
      left-text="返回"
      @click-left="router.push('/profile')"
    />
    <div class="account-content">
      <div class="account-eyebrow">我的旅程档案</div>
      <h1 class="account-heading">想去的地方，都在这里。</h1>
      <p class="account-description">
        共 {{ total }} 份已保存行程，随时查看或继续咨询。
      </p>
      <van-button type="primary" block round @click="router.push('/')"
        >规划新行程</van-button
      >
      <p v-if="error" class="account-error" role="alert">
        {{ error }} <button @click="load">重试</button>
      </p>
      <div v-if="loading" class="account-loading">
        <van-loading>正在读取行程...</van-loading>
      </div>
      <van-empty
        v-else-if="!items.length && !error"
        description="还没有保存的行程，先规划一趟旅行吧"
      />
      <article v-for="trip in items" :key="trip.id" class="archive-card">
        <h2>{{ trip.title }}</h2>
        <div class="archive-meta">
          <span>{{ trip.city }} · {{ trip.days }} 天</span
          ><span>¥{{ trip.budget }}</span
          ><span>版本 {{ trip.currentVersion }}</span>
        </div>
        <div class="archive-actions">
          <span class="archive-date">{{ date(trip.updatedAt) }}</span>
          <div>
            <van-button size="small" plain @click="remove(trip)"
              >删除</van-button
            >
            <van-button
              size="small"
              type="primary"
              @click="
                router.push({ path: '/detail', query: { tripId: trip.id } })
              "
              >查看行程</van-button
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
import { listTrips, deleteTrip } from "../utils/data";
import { messageOf } from "../utils/http";
import type { Trip } from "../types";
const router = useRouter(),
  items = ref<Trip[]>([]),
  page = ref(1),
  total = ref(0),
  loading = ref(false),
  error = ref("");
const date = (value: string) => new Date(value).toLocaleDateString("zh-CN");
let loadGeneration = 0, disposed = false;
const load = async () => {
  if (disposed) return;
  const current = ++loadGeneration, requestedPage = page.value;
  loading.value = true;
  error.value = "";
  try {
    const result = await listTrips(requestedPage);
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
const remove = async (trip: Trip) => {
  try {
    await showConfirmDialog({
      title: "删除行程",
      message: `删除“${trip.title}”及其版本？相关聊天记录会保留。`,
    });
  } catch {
    return;
  }
  try {
    await deleteTrip(trip.id);
    showToast("行程已删除");
    await load();
  } catch (e) {
    showToast(messageOf(e));
  }
};
onMounted(load);
onBeforeUnmount(() => { disposed = true; loadGeneration++; });
</script>
