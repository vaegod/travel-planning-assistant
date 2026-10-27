<template>
  <div class="page-container detail-page">
    <div class="page-header">
      <van-nav-bar
        left-text="返回"
        left-arrow
        @click-left="onBack"
        :title="formData.city + '行程规划'"
      />
    </div>
    <div class="page-content">
      <van-notice-bar
        v-if="saved"
        :text="`已保存 · 当前版本 ${saved.trip.currentVersion}`"
        left-icon="passed"
      />
      <div v-if="saved && saved.versions.length > 1" class="version-picker">
        <label for="trip-version">查看版本</label>
        <select
          id="trip-version"
          v-model.number="selectedVersion"
          :disabled="isLoading || saving || consulting"
          @change="loadVersion"
        >
          <option
            v-for="version in saved.versions"
            :key="version.version"
            :value="version.version"
          >
            版本 {{ version.version }}
          </option>
        </select>
        <span v-if="selectedVersion !== saved.trip.currentVersion"
          >咨询将使用最新保存的行程</span
        >
      </div>
      <div v-if="isLoading" class="loading-container">
        <van-loading size="48px" type="spinner">
          正在生成旅游规划...
        </van-loading>
      </div>
      <div v-else-if="errorMsg">
        <van-empty :description="errorMsg">
          <van-button type="primary" @click="fetchTripData">重试</van-button>
        </van-empty>
      </div>
      <template v-else-if="tripData && tripData.success !== false">
        <div v-if="tripData.agentEvidence" class="card agent-summary">
          <strong>智能规划 · 已通过结构与预算校验</strong>
          <p>{{ tripData.agentEvidence.budgetScope }}</p>
          <p>{{ tripData.agentEvidence.validation.warnings.filter(note => note !== tripData?.agentEvidence?.budgetScope).join('；') }}</p>
        </div>
        <router-link v-if="saved && selectedVersion === saved.trip.currentVersion"
          class="card agent-revise-link"
          :to="{ path: '/agent', query: { tripId: saved.trip.id } }">
          <span><strong>调整这份行程</strong><small>选择一天或一个时段，预览修改后保存新版本</small></span>
          <van-icon name="arrow" />
        </router-link>
        <van-notice-bar
          v-if="!tripData.evidence && !tripData.agentEvidence"
          text="AI生成的参考行程：景点、交通及价格未经实时核实，请以官方信息为准。"
        />
        <div v-if="tripData.evidence" class="card">
          <div>{{ tripData.evidence.attribution }}</div>
          <a
            class="estimate-note"
            href="https://www.openstreetmap.org/copyright"
            target="_blank"
            rel="noopener noreferrer"
            >OpenStreetMap 数据许可与归属</a
          >
          <p class="estimate-note">
            {{ tripData.evidence.priceNotice }}；预算余额 ¥{{
              tripData.remainingBudget
            }}
          </p>
        </div>
        <div class="card overview-card">
          <div class="trip-header">
            <h2>{{ tripData.city }} · {{ tripData.days }}天行程</h2>
            <span v-if="tripData.travelMode" class="travel-mode-badge">{{ travelModeLabel(tripData.travelMode) }}出行</span>
            <div class="trip-budget">预算：{{ tripData.totalBudget }}元</div>
          </div>
        </div>
        <van-collapse v-model="activeDays" class="trip-collapse">
          <van-collapse-item
            v-for="day in tripData.dailyItinerary"
            :key="day.day"
            :title="'第' + day.day + '天'"
            :name="day.day"
          >
            <div class="day-schedule">
              <div class="schedule-section">
                <div class="section-label morning">上午</div>
                <SpotItem
                  :data="day.morning"
                  :verified="tripData.evidence?.status === 'validated' || (!!tripData.agentEvidence && !!day.morning.sourceUrl)"
                  :source-label="tripData.agentEvidence ? '地点数据' : 'OpenStreetMap'"
                />
              </div>
              <div class="schedule-section">
                <div class="section-label afternoon">下午</div>
                <SpotItem
                  :data="day.afternoon"
                  :verified="tripData.evidence?.status === 'validated' || (!!tripData.agentEvidence && !!day.afternoon.sourceUrl)"
                  :source-label="tripData.agentEvidence ? '地点数据' : 'OpenStreetMap'"
                />
              </div>
              <div class="schedule-section">
                <div class="section-label evening">晚上</div>
                <SpotItem
                  :data="day.evening"
                  :verified="tripData.evidence?.status === 'validated' || (!!tripData.agentEvidence && !!day.evening.sourceUrl)"
                  :source-label="tripData.agentEvidence ? '地点数据' : 'OpenStreetMap'"
                />
              </div>
            </div>
          </van-collapse-item>
        </van-collapse>
        <div class="card budget-card" v-if="tripData.budgetBreakdown">
          <div class="section-title">预算明细</div>
          <BudgetTable
            :data="tripData.budgetBreakdown"
            :total="tripData.estimatedTotal ?? tripData.totalBudget"
            :caption="
              tripData.estimatedTotal != null
                ? '预计花费（估算）'
                : '总预算（AI参考估算）'
            "
          />
        </div>
        <div
          class="card tips-card"
          v-if="tripData.tips && tripData.tips.length"
        >
          <div class="section-title">温馨提示</div>
          <ul class="tips-list">
            <li v-for="(tip, index) in tripData.tips" :key="index">
              {{ tip }}
            </li>
          </ul>
        </div>
        <div
          class="card warnings-card"
          v-if="tripData.warnings && tripData.warnings.length"
        >
          <div class="section-title">注意事项</div>
          <ul class="warnings-list">
            <li v-for="(warning, index) in tripData.warnings" :key="index">
              {{ warning }}
            </li>
          </ul>
        </div>
      </template>
    </div>
    <div
      class="detail-footer"
      v-if="tripData && tripData.success !== false && !isLoading && !errorMsg"
    >
      <van-button
        plain
        type="primary"
        round
        :disabled="!!saved || saving"
        :loading="saving"
        @click="saveCurrent"
        >{{ saved ? "已保存" : "保存行程" }}</van-button
      >
      <van-button
        type="primary"
        round
        :loading="consulting"
        @click="goToChat"
        >{{ saved ? "咨询 AI 助手" : "保存并咨询 AI" }}</van-button
      >
    </div>
  </div>
</template>
<script setup lang="ts">
import { onMounted, onBeforeUnmount, reactive, ref } from "vue";
import { useRouter, useRoute } from "vue-router";
import { showToast } from "vant";
import {
  recommendTrip,
  cancelRecommendedTrip,
  saveTrip,
  getTrip,
  newConversation,
} from "../utils/data";
import { messageOf } from "../utils/http";
import { validateTrip } from "../utils/validation.js";
import type { TravelPlan, TripDetail } from "../types";
import SpotItem from "../components/SpotItem.vue";
import BudgetTable from "../components/BudgetTable.vue";
import { createTripVersionLoader } from "../utils/tripVersionLoader";
import { travelModeLabel } from "../utils/travelModes";
const router = useRouter(),
  route = useRoute();
const formData = reactive({ city: "", budget: 0, days: 0 });
const isLoading = ref(true),
  saving = ref(false),
  consulting = ref(false),
  errorMsg = ref(""),
  activeDays = ref([1]);
const tripData = ref<TravelPlan | null>(null),
  saved = ref<TripDetail | null>(null);
const selectedVersion = ref(1);
let fetchGeneration = 0, disposed = false;
let pendingPlan: {id: string; controller: AbortController} | null = null;
const stopPlanning = () => {
  if (!pendingPlan) return;
  const pending = pendingPlan;
  pendingPlan = null;
  pending.controller.abort();
  void cancelRecommendedTrip(pending.id).catch(() => {});
};
const onBack = () => router.back();
const fetchTripData = async () => {
  stopPlanning();
  const current = ++fetchGeneration;
  versionLoader.invalidate();
  isLoading.value = true;
  errorMsg.value = "";
  tripData.value = null;
  try {
    if (typeof route.query.tripId === "string") {
      const version = route.query.version
        ? Number(route.query.version)
        : undefined;
      const result = await getTrip(route.query.tripId, version);
      if (disposed || current !== fetchGeneration) return;
      saved.value = result;
      tripData.value = saved.value.plan;
      selectedVersion.value = version || saved.value.trip.currentVersion;
      Object.assign(formData, {
        city: tripData.value.city,
        budget: tripData.value.totalBudget,
        days: tripData.value.days,
      });
    } else {
      Object.assign(formData, {
        city: String(route.query.city || ""),
        budget: Number(route.query.budget),
        days: Number(route.query.days),
      });
      const invalid = validateTrip(formData);
      if (invalid) throw new Error(invalid);
      const pending = {id: crypto.randomUUID(), controller: new AbortController()};
      pendingPlan = pending;
      const result = await recommendTrip(
        formData.city,
        formData.days,
        formData.budget,
        {requestId: pending.id, signal: pending.controller.signal},
      );
      if (disposed || current !== fetchGeneration) return;
      saved.value = null;
      tripData.value = result;
    }
  } catch (e) {
    if (!disposed && current === fetchGeneration) errorMsg.value = messageOf(e);
  } finally {
    if (current === fetchGeneration) {
      pendingPlan = null;
      if (!disposed) isLoading.value = false;
    }
  }
};
const saveCurrent = async () => {
  if (disposed) return null;
  if (saved.value) return saved.value;
  if (!tripData.value) throw new Error("没有可保存的行程");
  saving.value = true;
  try {
    const result = await saveTrip(tripData.value);
    if (disposed) return result;
    saved.value = result;
    await router.replace({
      path: "/detail",
      query: { tripId: saved.value.trip.id },
    });
    if (!disposed) showToast("行程已保存");
    return saved.value;
  } catch (e) {
    if (!disposed) showToast(messageOf(e));
    return null;
  } finally {
    if (!disposed) saving.value = false;
  }
};
const versionLoader = createTripVersionLoader(
  getTrip,
  async (result, tripId, version) => {
    tripData.value = result.plan;
    selectedVersion.value = version;
    Object.assign(formData, {
      city: result.plan.city,
      budget: result.plan.totalBudget,
      days: result.plan.days,
    });
    await router.replace({
      path: "/detail",
      query: {
        tripId,
        version: String(version),
      },
    });
  },
  e => { errorMsg.value = messageOf(e); },
  loading => { isLoading.value = loading; if (loading) errorMsg.value = ""; },
);
const loadVersion = () => {
  if (!saved.value) return;
  fetchGeneration++;
  return versionLoader.load(saved.value.trip.id, selectedVersion.value);
};
const goToChat = async () => {
  if (disposed || consulting.value || saving.value) return;
  consulting.value = true;
  try {
    const trip = saved.value || (await saveCurrent());
    if (!trip || disposed) return;
    const conversation = await newConversation(trip.trip.id);
    if (disposed) return;
    await router.push(`/chat/${conversation.id}`);
  } catch (e) {
    if (!disposed) showToast(messageOf(e));
  } finally {
    if (!disposed) consulting.value = false;
  }
};
onMounted(fetchTripData);
onBeforeUnmount(() => { disposed = true; fetchGeneration++; versionLoader.stop(); stopPlanning(); });
</script>
<style scoped>
.travel-mode-badge { color:#41664d; background:#edf5ee; font-size:12px; border-radius:20px; padding:5px 12px; }
.agent-summary { border-left:3px solid #557c62; color:#41664d; }
.agent-summary p { font-size:12px; line-height:1.8; margin-bottom:0; }
.agent-revise-link { display:flex; justify-content:space-between; align-items:center; color:#35634a; text-decoration:none; background:#f4f8f4; }
.agent-revise-link small { display:block; margin-top:6px; color:#708477; }
.page-header {
  min-height: 56px; flex-shrink: 0;
}
.overview-card {
  margin-bottom: 16px;
}

.trip-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.trip-header h2 {
  font-size: 20px;
  color: #323233;
  margin: 0;
}

.trip-budget {
  font-size: 16px;
  color: var(--primary);
  font-weight: 600;
}

.trip-collapse {
  margin-bottom: 16px;
}

.day-schedule {
  padding: 8px 0;
}

.schedule-section {
  margin-bottom: 16px;
}

.schedule-section:last-child {
  margin-bottom: 0;
}

.section-label {
  font-size: 14px;
  font-weight: 600;
  padding: 4px 8px;
  border-radius: 4px;
  display: inline-block;
  margin-bottom: 8px;
}

.section-label.morning {
  background: #fff7e6;
  color: #fa8c16;
}

.section-label.afternoon {
  background: #e6f7ff;
  color: #1890ff;
}

.section-label.evening {
  background: #f6ffed;
  color: #52c41a;
}

.budget-card,
.tips-card,
.warnings-card {
  margin-bottom: 16px;
}

.tips-list,
.warnings-list {
  list-style: none;
  padding: 0;
  margin: 0;
}

.tips-list li,
.warnings-list li {
  padding: 8px 0;
  color: #666;
  font-size: 14px;
  border-bottom: 1px solid #f5f5f5;
}

.tips-list li:last-child,
.warnings-list li:last-child {
  border-bottom: none;
}

.detail-footer {
  display: flex;
  gap: 10px;
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 12px 16px;
  background: #fff;
  box-shadow: 0 -2px 8px rgba(0, 0, 0, 0.05);
  max-width: 872px;
  z-index: 90;
  border: 1px solid var(--line);
  border-radius: 14px 14px 0 0;
  margin: 0 auto;
}

.detail-footer :deep(.van-button) {
  flex: 1;
}
.version-picker {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin: 12px 0;
  font-size: 13px;
  color: #61717e;
}
.version-picker select {
  padding: 6px 10px;
  border: 1px solid #dce5ec;
  border-radius: 6px;
  background: #fff;
}
.error-card {
  text-align: center;
  padding: 40px 16px;
}
.revision-box label {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 12px 0;
  font-size: 14px;
}
.revision-box select {
  max-width: 70%;
  padding: 7px;
  border: 1px solid #dce5ec;
  border-radius: 6px;
  background: white;
}
.estimate-note {
  font-size: 12px;
  color: #74818c;
  line-height: 1.7;
}
.detail-page { padding-bottom: 100px; }
.detail-page .page-content { max-width: 920px; }
.trip-collapse { border-radius: 16px; overflow: hidden; border: 1px solid var(--line); }
.trip-collapse :deep(.van-collapse-item__title) { font-weight: 650; padding: 18px 24px; }
.trip-collapse :deep(.van-collapse-item__content) { padding: 4px 24px 24px; }
.schedule-section { position: relative; border-left: 1px solid #dfe7f3; padding: 0 0 14px 20px; margin-left: 5px; }
.schedule-section:before { content: ""; position: absolute; left: -4px; top: 10px; width: 7px; height: 7px; border-radius: 50%; background: #96b0d9; box-shadow: 0 0 0 4px white; }
.schedule-section:last-child { padding-bottom: 0; }
.detail-footer .van-button { max-width: 300px; }
.detail-footer { justify-content: flex-end; }
@media (max-width: 599px) {
  .trip-header h2 { font-size: 19px; }
  .trip-budget { font-size: 14px; }
  .trip-collapse :deep(.van-collapse-item__content) { padding: 0 16px 20px; }
  .detail-footer { padding-bottom: calc(12px + env(safe-area-inset-bottom)); border-radius: 0; }
}
</style>
