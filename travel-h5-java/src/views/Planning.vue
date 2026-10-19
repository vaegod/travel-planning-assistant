<template>
  <main class="account-page">
    <van-nav-bar
      title="行程规划草稿"
      left-arrow
      left-text="返回"
      @click-left="back"
    />
    <div class="account-content">
      <div class="account-eyebrow">先核对，再出发</div>
      <h1 class="account-heading">
        {{
          job?.status === "ERROR"
            ? "这次规划未完成。"
            : job?.status === "CANCELLED"
              ? "本次规划已取消。"
              : job?.baseVersion
                ? "预览这次改动。"
                : job?.plan
                  ? "旅程草稿已就绪。"
                  : "正在整理你的旅程。"
        }}
      </h1>
      <p class="account-description">
        保存前不会覆盖已有行程。票价与交通费用是估算，公开地图不代表实时开放或报价。
      </p>
      <div
        v-if="error || job?.status === 'ERROR'"
        class="account-error"
        role="alert"
      >
        {{ error || job?.message }}
        <p>已有行程保持不变，可返回后重试。</p>
        <van-button v-if="error" size="small" @click="poller.refresh"
          >重新读取状态</van-button
        >
      </div>
      <div v-if="job" class="card status-card" role="status">
        <van-loading v-if="pending" size="20" /><span>{{ job.progress }}</span
        ><van-button v-if="pending" size="small" @click="cancel"
          >取消任务</van-button
        >
      </div>
      <details v-if="job?.traces.length" class="card query-record">
        <summary>查询与检查记录（{{ job.traces.length }}）</summary>
        <div
          v-for="(trace, index) in job.traces"
          :key="index"
          class="trace-row"
        >
          <strong>{{ toolName(trace.tool) }}</strong
          ><span
            >{{
              trace.status === "ok"
                ? "完成"
                : trace.status === "invalid"
                  ? "校验未通过"
                  : "失败"
            }}
            · {{ trace.durationMs }}ms</span
          >
          <p>{{ trace.detail }}</p>
        </div>
      </details>
      <template
        v-if="job?.plan && (job.status === 'READY' || job.status === 'SAVED')"
      >
        <section v-if="job.baseVersion" class="card">
          <h2 class="section-title">与版本 {{ job.baseVersion }} 比较</h2>
          <div
            v-for="change in job.changes"
            :key="change.path"
            class="change-row"
          >
            <strong>{{ change.label }}</strong
            ><span>{{ change.before }} → {{ change.after }}</span>
          </div>
          <p v-if="!job.changes.length">行程内容没有变化，查询来源已更新。</p>
        </section>
        <section class="card">
          <h2>{{ job.plan.city }} · {{ job.plan.days }} 天</h2>
          <div class="budget-ledger">
            <span
              >预算上限<strong>¥{{ job.plan.totalBudget }}</strong></span
            ><span
              >预计花费<strong>¥{{ job.plan.estimatedTotal }}</strong></span
            ><span
              >估算余额<strong>¥{{ job.plan.remainingBudget }}</strong></span
            >
          </div>
          <p class="source-note">
            {{ job.plan.evidence?.attribution }} ·
            {{ job.plan.evidence?.priceNotice }}
          </p>
          <a
            href="https://www.openstreetmap.org/copyright"
            target="_blank"
            rel="noopener noreferrer"
            >OpenStreetMap 数据许可与归属</a
          >
        </section>
        <section
          v-for="day in job.plan.dailyItinerary"
          :key="day.day"
          class="card"
        >
          <h2 class="section-title">第 {{ day.day }} 天</h2>
          <div
            v-for="(slot, index) in [day.morning, day.afternoon, day.evening]"
            :key="index"
          >
            <div class="slot-label">{{ ["上午", "下午", "晚上"][index] }}</div>
            <SpotItem :data="slot" :verified="true" />
          </div>
        </section>
        <section v-if="job.plan.budgetBreakdown" class="card">
          <h2 class="section-title">估算明细</h2>
          <BudgetTable
            :data="job.plan.budgetBreakdown"
            :total="job.plan.estimatedTotal"
            caption="预计花费（估算）"
          />
        </section>
        <section class="card">
          <h2 class="section-title">估算口径与待核实事项</h2>
          <p
            v-for="(line, index) in [
              ...(job.plan.tips || []),
              ...(job.plan.warnings || []),
            ]"
            :key="index"
            class="source-note"
          >
            {{ line }}
          </p>
        </section>
        <van-button
          v-if="job.status === 'READY'"
          block
          type="primary"
          round
          :loading="saving"
          :disabled="saving"
          @click="save"
          >{{
            job.baseVersion
              ? `确认保存为版本 ${job.baseVersion + 1}`
              : "确认保存行程"
          }}</van-button
        >
        <van-button v-else block type="primary" round @click="openSaved"
          >查看已保存版本</van-button
        >
      </template>
      <van-button
        v-if="job?.status === 'ERROR' || job?.status === 'CANCELLED'"
        block
        plain
        @click="back"
        >返回重新规划</van-button
      >
    </div>
  </main>
</template>
<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { getPlanning, cancelPlanning, savePlanning } from "../utils/planning";
import {
  createPlanningPoller,
  isPlanningPending,
} from "../utils/planningPoller";
import { messageOf } from "../utils/http";
import type { PlanningJob } from "../types";
import SpotItem from "../components/SpotItem.vue";
import BudgetTable from "../components/BudgetTable.vue";
const route = useRoute(),
  router = useRouter(),
  id = String(route.params.id);
const job = ref<PlanningJob | null>(null),
  error = ref(""),
  saving = ref(false);
const pending = computed(
  () => !!job.value && isPlanningPending(job.value.status),
);
const names: Record<string, string> = {
  search_places: "查询景点",
  place_source: "景点数据节点",
  route_places: "查询驾车路线",
  model_select: "AI选择与校验",
  reuse_saved_evidence: "复用已保存来源",
};
const toolName = (name: string) => names[name] || "规划处理";
const poller = createPlanningPoller(
  () => getPlanning(id),
  (value) => {
    error.value = "";
    job.value = value;
  },
  (e) => {
    error.value = messageOf(e);
  },
);
const cancel = async () => {
  poller.stop();
  try {
    await cancelPlanning(id);
    await poller.refresh();
  } catch (e) {
    error.value = messageOf(e);
  }
};
const back = () =>
  job.value?.tripId
    ? router.push({ path: "/detail", query: { tripId: job.value.tripId } })
    : router.push("/");
const openSaved = () =>
  job.value?.tripId &&
  router.replace({
    path: "/detail",
    query: {
      tripId: job.value.tripId,
      version: String(job.value.savedVersion || ""),
    },
  });
const save = async () => {
  saving.value = true;
  error.value = "";
  try {
    const result = await savePlanning(id);
    await router.replace({
      path: "/detail",
      query: { tripId: result.trip.id },
    });
  } catch (e) {
    error.value = messageOf(e);
  } finally {
    saving.value = false;
  }
};
onMounted(poller.refresh);
onBeforeUnmount(() => {
  poller.stop();
  if (!job.value || isPlanningPending(job.value.status))
    void cancelPlanning(id).catch(() => {});
});
</script>
<style scoped>
.status-card {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  font-size: 14px;
}
.budget-ledger {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin: 20px 0;
}
.budget-ledger span {
  font-size: 12px;
  color: #6c7a86;
}
.budget-ledger strong {
  display: block;
  font:
    23px Georgia,
    serif;
  color: #255d87;
  margin-top: 8px;
}
.source-note {
  font-size: 12px;
  color: #687987;
  line-height: 1.8;
}
.card a {
  font-size: 12px;
  color: #256b9d;
}
.slot-label {
  background: #edf4f9;
  color: #376b92;
  font-size: 12px;
  display: inline-block;
  padding: 4px 8px;
  border-radius: 5px;
  margin-top: 12px;
}
.change-row {
  display: flex;
  justify-content: space-between;
  gap: 15px;
  font-size: 13px;
  border-bottom: 1px solid #edf0f2;
  padding: 12px 0;
}
.change-row span {
  color: #657b8b;
  max-width: 65%;
  overflow-wrap: anywhere;
}
.trace-row {
  font-size: 12px;
  padding: 12px 0;
  border-bottom: 1px solid #edf0f2;
}
.trace-row span {
  float: right;
  color: #728391;
}
.trace-row p {
  color: #758390;
  line-height: 1.7;
}
.query-record summary {
  font-size: 14px;
  cursor: pointer;
}
</style>
