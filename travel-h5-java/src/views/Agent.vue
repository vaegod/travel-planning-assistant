<template>
  <main class="page-container agent-page">
    <div class="page-header"><van-nav-bar title="智能规划" left-arrow left-text="返回" @click-left="router.push(base?.trip.id || job?.tripId ? '/detail?tripId=' + (base?.trip.id || job?.tripId) : '/')" /></div>
    <div class="page-content agent-content">
      <header class="agent-heading">
        <span class="agent-eyebrow">PLAN WITH PURPOSE</span>
        <h1>{{ base || job?.baseVersion ? '让计划，更合你意。' : '把想法，变成好走的旅程。' }}</h1>
        <p>{{ base || job?.baseVersion ? '选定要调整的部分，比较变化，再保存新版本。' : '说出你的偏好，智能体会核算费用、检查安排，再交给你确认。' }}</p>
      </header>
      <p v-if="loading" role="status" class="loading-note">正在读取规划信息…</p>
      <div v-if="error" class="agent-error" role="alert">
        <span>{{ error }}</span>
        <button v-if="job || route.params.id" @click="refresh">重新读取任务</button>
      </div>
      <div class="agent-layout">
        <section class="card agent-form" v-if="!job && !route.params.id && !loading && capabilities">
          <h2>{{ base ? '调整已保存行程' : '这次想怎么旅行' }}</h2>
          <div v-if="base" class="base-trip">
            <strong>{{ base.trip.title }}</strong>
            <span>{{ base.trip.city }} · {{ base.trip.days }}天 · 版本 {{ base.trip.currentVersion }}</span>
          </div>
          <template v-else>
            <label class="field-label" for="agent-city">目的地</label>
            <input id="agent-city" v-model.trim="form.city" maxlength="50" placeholder="例如：杭州" :disabled="submitting" />
            <div class="agent-fields">
              <label>天数<input v-model.number="form.days" type="number" min="1" :max="capabilities.maxDays" :disabled="submitting" /></label>
              <label>单人预算（元）<input v-model.number="form.budget" type="number" min="100" max="1000000" step="0.01" :disabled="submitting" /></label>
            </div>
          </template>
          <fieldset v-if="!base" class="travel-mode-picker" :disabled="submitting">
            <legend>出行方式</legend>
            <div class="travel-mode-options">
              <label v-for="mode in travelModes" :key="mode.value" :class="{ selected: form.travelMode === mode.value }">
                <input v-model="form.travelMode" type="radio" name="travel-mode" :value="mode.value" />
                <strong>{{ mode.label }}</strong><small>{{ mode.description }}</small>
              </label>
            </div>
          </fieldset>
          <div v-else class="inherited-mode">
            <span>路线查询方式：<strong>{{ travelModeLabel(form.travelMode) }}</strong></span>
            <small>{{ base.plan.travelMode ? '局部修改沿用原行程的出行方式。' : '旧行程未设置出行方式，本次调整默认使用步行。' }}</small>
          </div>
          <div v-if="base" class="agent-fields">
            <label>选择日期<select v-model.number="form.day" :disabled="submitting"><option v-for="day in base.trip.days" :key="day" :value="day">第 {{ day }} 天</option></select></label>
            <label>修改范围<select v-model="form.slot" :disabled="submitting"><option value="day">整天</option><option value="morning">上午</option><option value="afternoon">下午</option><option value="evening">晚上</option></select></label>
          </div>
          <label class="field-label" for="agent-instruction">{{ base ? '想怎样调整' : '旅行偏好' }}</label>
          <textarea id="agent-instruction" v-model.trim="form.instruction" maxlength="1000" rows="4" :disabled="submitting"
            :placeholder="base ? '例如：换成博物馆，减少步行，费用不要超过原预算' : '例如：喜欢博物馆和公园，节奏轻松，每天留时间休息'" />
          <label class="field-label" for="agent-visit-minimum">景点最短游览时间（可选）</label>
          <textarea id="agent-visit-minimum" v-model="form.visitText" maxlength="1200" rows="2" :disabled="submitting" placeholder="如：故宫博物院 180分钟，每行一个景点" />
          <p class="draft-note">安排到对应景点时遵守下限，不会强制加入这些景点。{{ base ? '留空沿用原行程已有下限；填写后采用本次填写的下限。' : '此项为你提供的游览要求，未提供时仅检查通用范围。' }}</p>
          <label class="field-label" for="agent-ticket-minimum">门票预留参考（可选）</label>
          <textarea id="agent-ticket-minimum" v-model="form.ticketText" maxlength="1200" rows="2" :disabled="submitting" placeholder="如：正佳雨林生态植物园 98元，每行一个景点" />
          <p class="draft-note">安排到对应景点时，门票预留不低于此金额。这是你提供的预算参考，实际价格请另行确认。{{ base ? '留空沿用原行程已有参考。' : '' }}</p>
          <p class="scope-note">{{ capabilities.budgetScope }}</p>
          <van-button type="primary" block round :loading="submitting" @click="submit">{{ base ? '生成修改草稿' : '开始智能规划' }}</van-button>
          <p class="draft-note">生成草稿后由你确认保存。新建支持 1～{{ capabilities.maxDays }} 天；已保存行程可按日期局部调整。</p>
        </section>
        <section v-if="job" class="agent-results">
          <div class="card run-status" :class="job.status.toLowerCase()" aria-live="polite">
            <span class="status-mark"><van-loading v-if="pending" size="22" /><van-icon v-else :name="job.status === 'READY' || job.status === 'SAVED' ? 'passed' : 'info-o'" /></span>
            <div><h2>{{ statusTitle }}</h2><p>{{ job.progress }}</p></div>
            <van-button v-if="pending || job.status === 'READY'" size="small" plain :loading="cancelling" :disabled="saving" @click="cancel">{{ pending ? '停止' : '放弃草稿' }}</van-button>
          </div>
          <div v-if="job.validation && !job.validation.valid" class="card validation-issues">
            <strong>{{ pending ? '正在修正这些问题' : '本次校验发现的问题' }}</strong>
            <ul><li v-for="issue in job.validation.issues" :key="issue">{{ issue }}</li></ul>
          </div>
          <div v-if="job.changes.length" class="card changes-card">
            <h2>修改前后</h2>
            <div v-for="change in job.changes" :key="change.path" class="change">
              <strong>{{ change.label }}</strong><div class="before">{{ change.before }}</div><div class="after">{{ change.after }}</div>
            </div>
          </div>
          <template v-if="job.plan">
            <div class="card plan-overview">
              <div><span class="agent-eyebrow">YOUR ITINERARY</span><h2>{{ job.plan.city }} · {{ job.plan.days }}天</h2><span class="mode-badge">{{ travelModeLabel(job.travelMode) }}出行</span></div>
              <div class="budget-total"><small>预计花费 / 单人</small><strong>¥{{ job.plan.estimatedTotal }}</strong><span>预算余额 ¥{{ job.plan.remainingBudget }}</span></div>
            </div>
            <van-collapse v-model="openDays" class="agent-days">
              <van-collapse-item v-for="day in job.plan.dailyItinerary" :key="day.day" :name="day.day" :title="'第' + day.day + '天'">
                <div v-for="slot in slots" :key="slot.key" class="agent-slot">
                  <h3>{{ slot.label }}</h3><SpotItem :data="day[slot.key]" :verified="!!day[slot.key].sourceUrl" source-label="地点数据" />
                  <small v-if="day[slot.key].visitMinutes != null">游览 {{ day[slot.key].visitMinutes }} 分钟 · 交通 {{ day[slot.key].travelMinutes ?? '未知' }} 分钟（以查询或估算说明为准）</small>
                </div>
              </van-collapse-item>
            </van-collapse>
            <div v-if="job.plan.budgetBreakdown" class="card"><h2>预算核算</h2><BudgetTable :data="job.plan.budgetBreakdown" :total="job.plan.estimatedTotal" caption="预计花费（估算）" /></div>
            <div class="card plan-notes"><strong>校验与数据说明</strong><ul><li v-for="note in job.validation?.warnings" :key="note">{{ note }}</li></ul></div>
            <div class="agent-actions">
              <van-button v-if="job.status === 'READY'" type="primary" round :loading="saving" :disabled="cancelling" @click="save">{{ job.baseVersion ? '确认修改并保存新版本' : '确认保存行程' }}</van-button>
              <van-button v-if="job.status === 'SAVED'" type="primary" round @click="viewSaved">查看已保存行程</van-button>
            </div>
          </template>
          <router-link v-if="!pending" class="restart-link" :to="job.baseVersion && job.tripId ? { path: '/agent', query: { tripId: job.tripId } } : { path: '/agent' }">重新发起规划</router-link>
        </section>
        <aside class="agent-sidebar">
          <section class="card tool-timeline">
            <div class="timeline-heading"><h2>执行记录</h2><span v-if="job">{{ job.toolCalls }} 次模型工具调用</span></div>
            <p v-if="!job?.traces.length" class="empty-trace">开始后，你可以在这里看到实际完成的查询、核算和校验。</p>
            <ol v-else><li v-for="(trace, index) in job.traces" :key="index" :class="trace.status"><span class="trace-dot"></span><div><strong>{{ toolNames[trace.tool] || trace.tool }}</strong><small>{{ trace.status === 'ok' ? '完成' : trace.status === 'invalid' ? '需要调整' : trace.status === 'refused' ? '不可行' : trace.status === 'retry' ? '已发起重连' : '未完成' }} · {{ trace.durationMs }} ms</small><p>{{ trace.detail }}</p></div></li></ol>
          </section>
          <section v-if="capabilities" class="card capability-note">
            <strong>{{ capabilities.mapsConfigured ? '已连接地图查询' : '当前生成参考行程' }}</strong>
            <p>{{ capabilities.mapsConfigured ? '可查询地点及所选方式的路线。公交班次、景点开放和预约条件请在出行前核实。' : '地点与交通使用模型参考建议，未经实时查询；费用和行程约束由程序核算。' }}</p>
            <p>未保存草稿暂存 {{ capabilities.draftRetentionMinutes }} 分钟，服务重启后需重新生成。</p>
          </section>
        </aside>
      </div>
    </div>
  </main>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { showToast } from "vant";
import { auth } from "../utils/auth";
import { getTrip } from "../utils/data";
import { messageOf } from "../utils/http";
import { agentCapabilities, createAgent, reviseAgent, getAgent, cancelAgent, saveAgent, type AgentCapabilities } from "../utils/agent";
import { createPlanningPoller, isPlanningPending } from "../utils/planningPoller";
import type { AgentJob, TripDetail } from "../types";
import SpotItem from "../components/SpotItem.vue";
import BudgetTable from "../components/BudgetTable.vue";
import { travelModes, normalizeTravelMode, travelModeLabel, type TravelMode } from "../utils/travelModes";
import { parseVisitRequirements } from "../utils/visitRequirements";
import { parseTicketRequirements } from "../utils/ticketRequirements";
const route = useRoute(), router = useRouter();
const job = ref<AgentJob | null>(null), base = ref<TripDetail | null>(null), capabilities = ref<AgentCapabilities | null>(null);
const loading = ref(false), submitting = ref(false), saving = ref(false), cancelling = ref(false), error = ref("");
const form = reactive({ city: "", days: 3, budget: 3000, instruction: "", visitText:"", ticketText:"", day: 1, slot: "afternoon", travelMode: "walking" as TravelMode });
const openDays = ref([1]);
const slots = [{ key: "morning", label: "上午" }, { key: "afternoon", label: "下午" }, { key: "evening", label: "晚上" }] as const;
const toolNames: Record<string, string> = { search_places: "查询景点", route_places: "查询路线", estimate_budget: "核算费用", check_budget: "核验必要费用", validate_itinerary: "校验行程", verify_place: "核验地点", verify_route: "核验相邻路线", retry_map: "重连地图服务", report_infeasible: "说明不可行原因", continue_planning:"继续提交草稿", recover_model:"恢复规划连接" };
const pending = computed(() => !!job.value && isPlanningPending(job.value.status));
const statusTitle = computed(() => ({ QUEUED: "等待规划", RUNNING: "智能体正在处理", READY: "草稿已就绪", SAVED: "行程已保存", ERROR: "本次规划未完成", CANCELLED: "规划已停止", REFUSED: "当前要求暂无法完成" })[job.value?.status || "QUEUED"]);
let epoch = 0, disposed = false;
let retry: { key: string; requestId: string } | null = null;
let poller: ReturnType<typeof createPlanningPoller<AgentJob>> | null = null;
const current = (value: number) => !disposed && epoch === value;
function startPoll(id: string, generation: number) {
  poller?.stop();
  poller = createPlanningPoller<AgentJob>(() => getAgent(id), result => {
    if (!current(generation)) return;
    job.value = result; error.value = "";
  }, e => { if (current(generation)) error.value = messageOf(e); }, 1200);
  void poller.refresh();
}
async function initialize() {
  const generation = ++epoch;
  poller?.stop(); job.value = null; base.value = null; capabilities.value = null;
  error.value = ""; loading.value = true; submitting.value = false; saving.value = false; cancelling.value = false; retry = null;
  form.visitText="";
  form.ticketText="";
  try {
    const cap = await agentCapabilities();
    if (!current(generation)) return;
    capabilities.value = cap;
    if (typeof route.params.id === "string" && route.params.id) startPoll(route.params.id, generation);
    else if (typeof route.query.tripId === "string") {
      const trip = await getTrip(route.query.tripId);
      if (!current(generation)) return;
      base.value = trip; form.day = 1; form.slot = "afternoon"; form.instruction = "";
      form.travelMode = normalizeTravelMode(trip.plan.travelMode);
    } else {
      form.city = String(route.query.city || "");
      form.days = Math.min(cap.maxDays, Math.max(1, Number(route.query.days) || 3));
      form.budget = Number(route.query.budget) || 3000; form.instruction = "";
      form.travelMode = normalizeTravelMode(route.query.travelMode);
    }
  } catch (e) { if (current(generation)) error.value = messageOf(e); }
  finally { if (current(generation)) loading.value = false; }
}
async function submit() {
  if (submitting.value || !capabilities.value) return;
  if (!base.value && (!form.city.trim() || form.city.length > 50 || !Number.isInteger(form.days) ||
      form.days < 1 || form.days > capabilities.value.maxDays || !Number.isFinite(form.budget) || form.budget < 100 ||
      form.budget > 1000000 || Math.abs(form.budget * 100 - Math.round(form.budget * 100)) > 0.000001)) {
    error.value = "请填写目的地、有效天数和100～1000000元的预算（最多两位小数）"; return;
  }
  if (base.value && !form.instruction.trim()) { error.value = "请填写你希望怎样调整行程"; return; }
  let visitRequirements,ticketRequirements;
  try {
    visitRequirements=form.visitText.trim()?parseVisitRequirements(form.visitText):undefined;
    ticketRequirements=form.ticketText.trim()?parseTicketRequirements(form.ticketText):undefined;
  }
  catch(e) { error.value=messageOf(e);return; }
  const generation = epoch, trip = base.value;
  const payload = trip ? { baseVersion: trip.trip.currentVersion, day: form.day, slot: form.slot, instruction: form.instruction,visitRequirements,ticketRequirements }
    : { city: form.city.trim(), days: form.days, budget: form.budget, instruction: form.instruction, travelMode: form.travelMode,visitRequirements,ticketRequirements };
  const key = JSON.stringify([trip?.trip.id, payload]);
  if (!retry || retry.key !== key) retry = { key, requestId: crypto.randomUUID() };
  submitting.value = true; error.value = "";
  try {
    const result = trip
      ? await reviseAgent(trip.trip.id, { requestId: retry.requestId, baseVersion: trip.trip.currentVersion, day: form.day, slot: form.slot, instruction: form.instruction,visitRequirements,ticketRequirements })
      : await createAgent({ requestId: retry.requestId, city: form.city.trim(), days: form.days, budget: form.budget, instruction: form.instruction, travelMode: form.travelMode,visitRequirements,ticketRequirements });
    if (!current(generation)) return;
    retry = null;
    await router.replace("/agent/" + result.id);
  } catch (e) { if (current(generation)) error.value = messageOf(e); }
  finally { if (current(generation)) submitting.value = false; }
}
async function cancel() {
  if (!job.value || cancelling.value || saving.value) return;
  const generation = epoch, id = job.value.id;
  cancelling.value = true; error.value = ""; poller?.stop();
  try { await cancelAgent(id); if (current(generation)) startPoll(id, generation); }
  catch (e) { if (current(generation)) { error.value = messageOf(e); startPoll(id, generation); } }
  finally { if (current(generation)) cancelling.value = false; }
}
async function save() {
  if (!job.value || saving.value || cancelling.value) return;
  const generation = epoch, id = job.value.id;
  saving.value = true; error.value = "";
  try {
    const saved = await saveAgent(id);
    if (!current(generation)) return;
    showToast("已保存");
    await router.push({ path: "/detail", query: { tripId: saved.trip.id, version: saved.trip.currentVersion } });
  } catch (e) { if (current(generation)) error.value = messageOf(e); }
  finally { if (current(generation)) saving.value = false; }
}
function viewSaved() {
  if (job.value?.tripId) void router.push({ path: "/detail", query: { tripId: job.value.tripId, version: job.value.savedVersion } });
}
function refresh() { if (route.params.id) startPoll(String(route.params.id), epoch); else void initialize(); }
watch(() => [route.fullPath, auth.user?.id], initialize, { immediate: true });
onBeforeUnmount(() => { disposed = true; epoch++; poller?.stop(); });
</script>

<style scoped>
.agent-page { --agent-green:#3c654d; padding-bottom:42px; }
.agent-content { max-width:1120px; }
.agent-heading { margin:20px 0 28px; }
.agent-eyebrow { color:#7d8c7f; font-size:10px; letter-spacing:2px; font-weight:700; }
.agent-heading h1 { margin:10px 0; font-size:clamp(25px,3vw,34px); letter-spacing:-.7px; color:#2b4836; }
.agent-heading p { color:#708077; line-height:1.8; font-size:14px; }
.agent-layout { display:grid; grid-template-columns:minmax(0,1fr) 295px; gap:24px; align-items:start; }
.agent-page h2 { font-size:17px; margin:0 0 18px; }
.agent-page .card { padding:24px; border-radius:14px; }
.agent-form label,.field-label { display:block; font-size:13px; color:#53675b; margin-bottom:9px; }
.agent-form input,.agent-form select,.agent-form textarea { box-sizing:border-box; width:100%; background:#fafcf9; border:1px solid #dce5dc; border-radius:8px; padding:12px; color:#283e30; font:inherit; line-height:1.5; }
.agent-form input:focus,.agent-form select:focus,.agent-form textarea:focus { outline:2px solid #91af99; outline-offset:2px; }
.agent-fields { display:grid; grid-template-columns:1fr 1fr; gap:18px; margin:20px 0; }
.agent-fields input,.agent-fields select { margin-top:8px; }
.agent-form textarea { resize:vertical; min-height:120px; }
.travel-mode-picker { border:0; padding:0; margin:22px 0; min-width:0; }
.travel-mode-picker legend { color:#53675b; font-size:13px; margin-bottom:10px; }
.travel-mode-options { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; }
.travel-mode-options label { margin:0; padding:12px 9px; border:1px solid #dce5dc; border-radius:10px; cursor:pointer; background:#fafcf9; }
.travel-mode-options label.selected { border-color:#5d8769; background:#edf5ee; box-shadow:0 0 0 1px #5d8769; }
.travel-mode-options input { width:auto; accent-color:var(--agent-green); margin:0 7px 0 0; vertical-align:middle; }
.travel-mode-options strong { font-size:14px; }
.travel-mode-options small { display:block; margin-top:8px; font-size:11px; color:#7b887e; line-height:1.5; }
.inherited-mode { margin:18px 0; padding:13px 15px; background:#f4f7f2; border-radius:8px; color:#4d6754; font-size:13px; }
.inherited-mode small { display:block; margin-top:7px; color:#7b887e; font-size:12px; }
.mode-badge { display:inline-block; margin-top:10px; padding:4px 10px; border-radius:20px; background:#edf5ee; color:#4d7155; font-size:12px; }
.scope-note,.draft-note { font-size:12px; line-height:1.9; color:#7a897e; margin:15px 0; }
.agent-page :deep(.van-button--primary) { background:var(--agent-green); border-color:var(--agent-green); }
.base-trip { border-left:3px solid #789681; background:#f4f7f2; padding:14px; }
.base-trip strong,.base-trip span { display:block; }
.base-trip span { color:#7a897e; margin-top:8px; font-size:12px; }
.run-status { display:flex; align-items:center; gap:16px; border-left:3px solid #91a68e; }
.run-status h2 { margin:0 0 6px; }
.run-status p { font-size:13px; color:#708077; margin:0; line-height:1.7; }
.run-status>div { flex:1; }
.status-mark { font-size:26px; color:var(--agent-green); }
.run-status.error { border-left-color:#bf7156; }
.run-status.refused { border-left-color:#b08c55; }
.run-status p { white-space:pre-line; overflow-wrap:anywhere; }
.agent-error { padding:14px 18px; background:#fff3ec; border:1px solid #edd9ca; border-radius:10px; margin:16px 0; color:#8b503e; font-size:14px; line-height:1.7; display:flex; justify-content:space-between; gap:12px; }
.agent-error button { border:0; background:transparent; color:inherit; text-decoration:underline; cursor:pointer; flex-shrink:0; }
.timeline-heading { display:flex; align-items:baseline; justify-content:space-between; gap:8px; }
.timeline-heading span { font-size:11px; color:#8b978c; }
.empty-trace,.capability-note p { font-size:12px; line-height:1.9; color:#7b887e; }
.tool-timeline ol { padding:0; margin:4px 0 0; list-style:none; }
.tool-timeline li { display:flex; gap:12px; position:relative; padding:0 0 20px; }
.trace-dot { width:8px; height:8px; border-radius:50%; background:#72927b; margin-top:5px; flex-shrink:0; }
.tool-timeline li:not(:last-child):before { content:""; position:absolute; left:3px; top:16px; bottom:5px; width:1px; background:#dfe7df; }
.tool-timeline li.error .trace-dot,.tool-timeline li.invalid .trace-dot { background:#c49a65; }
.tool-timeline strong { display:block; font-size:13px; color:#4e6556; }
.tool-timeline small { font-size:10px; color:#8c988f; }
.tool-timeline p { margin:6px 0 0; font-size:12px; color:#7b887e; line-height:1.7; overflow-wrap:anywhere; }
.capability-note { background:#f2f5ef; border-color:#e2e8dd; }
.capability-note strong { font-size:13px; color:#5e7563; }
.plan-overview { display:flex; justify-content:space-between; gap:20px; align-items:center; }
.plan-overview h2 { margin:8px 0 0; font-size:22px; }
.budget-total { text-align:right; }
.budget-total small,.budget-total span { display:block; font-size:11px; color:#7c8c7e; }
.budget-total strong { display:block; color:var(--agent-green); font-size:27px; margin:4px 0; }
.agent-days { border:1px solid var(--line); border-radius:14px; overflow:hidden; margin-bottom:20px; }
.agent-slot { border-bottom:1px solid #edf0e9; padding:4px 0 18px; margin-bottom:14px; }
.agent-slot:last-child { border-bottom:0; margin-bottom:0; }
.agent-slot h3 { font-size:12px; color:#7d947f; font-weight:600; margin-bottom:0; }
.agent-slot small { font-size:11px; color:#809185; }
.plan-notes,.validation-issues { font-size:12px; color:#718077; line-height:1.9; }
.plan-notes ul,.validation-issues ul { padding-left:18px; }
.validation-issues { background:#fffbf2; }
.change { margin:16px 0; font-size:12px; line-height:1.8; overflow-wrap:anywhere; }
.change strong { display:block; margin-bottom:7px; }
.before { padding:9px 12px; background:#f7f4f0; color:#8c8278; border-radius:6px; }
.after { padding:9px 12px; margin-top:6px; background:#edf5ef; color:#48634d; border-radius:6px; }
.agent-actions { padding:14px; background:#fff; border:1px solid #e0e7dd; border-radius:12px; text-align:right; box-shadow:0 5px 25px #263b3010; }
.restart-link { display:block; text-align:center; margin:22px 0; color:#64806b; font-size:13px; }
.loading-note { color:#7a897e; }
@media(max-width:800px) { .agent-layout { grid-template-columns:minmax(0,1fr); } .agent-sidebar { order:-1; } .tool-timeline { max-height:230px; overflow:auto; } .capability-note { display:none; } .agent-page .card { padding:20px; } }
@media(max-width:450px) { .agent-heading { margin:14px 0 22px; } .agent-fields { gap:12px; } .agent-actions .van-button { width:100%; } .plan-overview { gap:8px; } .budget-total strong { font-size:23px; } }
</style>

