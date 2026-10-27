<template>
  <main class="page-container home-page">
    <div class="page-header home-mobile-header"><van-nav-bar title="旅游规划智能助手" /></div>
    <div class="page-content home-content">
      <section class="home-hero">
        <div class="hero-copy">
          <div class="hero-eyebrow"><span></span> 每一次出发，都值得好好计划</div>
          <h1>下一站，<span>去看看世界。</span></h1>
          <p>选一个想去的地方，把时间交给旅途。<br class="mobile-break" />让 AI 帮你安排沿途的美好。</p>
          <div class="hero-features"><span><van-icon name="location-o" />全国目的地</span><span><van-icon name="chat-o" />随时聊灵感</span><span><van-icon name="notes-o" />保存好计划</span></div>
        </div>
        <div class="travel-art" aria-hidden="true">
          <svg viewBox="0 0 420 220" fill="none">
            <circle cx="274" cy="89" r="73" fill="#e7effb" />
            <circle cx="303" cy="58" r="18" fill="#f2c17e" />
            <path d="M118 166 188 81 245 149 274 113 331 166" fill="#cedff1" />
            <path d="m188 81 57 68 29-36 57 53H118l70-85Z" stroke="#83a8ce" stroke-width="1.5" stroke-linejoin="round" />
            <path d="m171 102 17-21 22 26-22-8-17 3Z" fill="#f5f8fd" />
            <path d="M80 183h279M137 175h185" stroke="#afc5df" stroke-linecap="round" />
            <path d="M101 137c-44-30-22-71 25-59 33 9 8 42-3 62-21 38 63 45 95 22 25-17 49-23 79-12" stroke="#2763dc" stroke-width="2" stroke-dasharray="5 6" />
            <circle cx="101" cy="137" r="6" fill="#2763dc" stroke="white" stroke-width="3" />
            <path d="M322 120c0 16-16 32-16 32s-16-16-16-32a16 16 0 1 1 32 0Z" fill="#2763dc" />
            <circle cx="306" cy="119" r="5" fill="white" />
            <path d="m124 52 19-6-6 19-5-8-8-5Z" fill="#2763dc" />
            <path d="m132 57 11-11" stroke="white" stroke-linecap="round" />
          </svg>
          <div class="art-ticket"><van-icon name="guide-o" /><span>YOUR NEXT CHAPTER<strong>下一站，值得期待</strong></span><i>✦</i></div>
        </div>
      </section>
      <div class="home-workspace">
        <section class="card search-card">
          <div class="planner-heading"><span class="section-icon"><van-icon name="guide-o" /></span><div><h2 class="section-title">规划你的旅程</h2><p>告诉我们目的地，其余的交给灵感。</p></div><span class="step-badge">01 / 出发准备</span></div>
          <van-field v-model="formData.city" label="目的地" placeholder="输入目的地或从全国城市中选择" maxlength="50" class="planner-field">
            <template #button><van-button size="small" plain type="primary" @click="openCityPicker">选择城市 <van-icon name="arrow" /></van-button></template>
          </van-field>
          <div class="planner-row">
            <van-field v-model="formData.budget" type="number" label="预算（元）" placeholder="例如 3000" :border="false" class="planner-field" />
            <van-field v-model="formData.days" type="digit" label="天数" placeholder="例如 3" :border="false" class="planner-field" />
          </div>
          <van-button type="primary" size="large" round :loading="isLoading" @click="handleSubmit" class="plan-submit">开始规划 <van-icon name="arrow" /></van-button>
          <p class="planner-notice"><van-icon name="info-o" />AI 生成参考行程，地点、交通和价格请以官方信息为准。</p>
          <router-link class="agent-entry" :to="{ path: '/agent', query: { city: formData.city, days: formData.days || 3, budget: formData.budget || 3000 } }">
            <span><strong>试试智能规划</strong><small>提出偏好 · 工具核算 · 校验后生成草稿</small></span><van-icon name="arrow" />
          </router-link>
        </section>
        <aside class="home-support">
          <section class="card popular-destinations">
            <div class="destination-heading"><h2 class="section-title">从这些地方开始</h2><button class="text-link" @click="openCityPicker">全国城市 <van-icon name="arrow" /></button></div>
            <div class="destination-grid"><button v-for="(city, index) in popularCities" :key="city" @click="selectedCity(city)" class="city-tile" :class="{ active: formData.city === city || findDestination(formData.city)?.name.replace(/市$/, '') === city }" :aria-pressed="formData.city === city || findDestination(formData.city)?.name.replace(/市$/, '') === city"><span class="city-symbol"><van-icon :name="['flag-o', 'hotel-o', 'flower-o', 'smile-o'][index]" /></span><span><strong>{{ city }}</strong><small>{{ ['古都漫游', '城市灵感', '山水慢行', '烟火日常'][index] }}</small></span><van-icon name="arrow" /></button></div>
          </section>
          <section class="card quick-actions">
            <h2 class="section-title">旅行，也可以这样开始</h2>
            <div class="quick-links"><button @click="goPage('/chat')"><span class="quick-icon"><van-icon name="chat-o" /></span><span><strong>聊聊旅行灵感</strong><small>景点、美食、出行建议，随时问 AI</small></span><van-icon name="arrow" /></button><button @click="goPage('/trips')"><span class="quick-icon mint"><van-icon name="notes-o" /></span><span><strong>查看我的行程</strong><small>找回已经保存的旅程安排</small></span><van-icon name="arrow" /></button></div>
          </section>
        </aside>
      </div>
      <footer class="home-footnote"><span>PLAN A LITTLE. EXPLORE A LOT.</span> 好好计划，自在出发。</footer>
    </div>
    <van-popup v-model:show="showCityPicker" position="bottom" round class="city-popup">
      <section class="city-picker" role="dialog" aria-modal="true" aria-label="全国城市选择">
        <div class="city-picker-header"><div><p>从这里出发</p><h2>选择目的地</h2></div><van-button size="small" plain @click="showCityPicker = false">取消</van-button></div>
        <van-search v-model="cityQuery" label="搜索" placeholder="搜索城市或省份，例如：西安" aria-label="搜索城市或省份" />
        <p class="city-picker-note">按省份选择城市，也可搜索；未列出的区县或景区可以在首页手动输入。</p>
        <template v-if="cityQuery.trim()"><p class="city-search-count">找到 {{ cityMatches.length }} 个目的地</p><div class="city-search-results"><van-cell v-for="city in cityMatches" :key="city.code" :title="city.name" :label="city.provinceName" is-link clickable @click="chooseDestination(city.code)" /><van-empty v-if="!cityMatches.length" description="未找到匹配项，可取消后手动输入目的地" /></div></template>
        <van-cascader v-else v-model="cityCode" :options="cityOptions" :show-header="false" active-color="var(--primary)" placeholder="请选择省份 / 城市" @finish="({ value }) => chooseDestination(String(value))" />
      </section>
    </van-popup>
  </main>
</template>
<script setup>
import { ref, reactive, computed } from "vue";
import { useRouter } from "vue-router";
import { showToast } from "vant";
import { validateTrip } from "../utils/validation.js";
import {
  cityOptions,
  destinationByCode,
  findDestination,
  searchDestinations,
} from "../utils/cityCatalog";

// 路由实例
const router = useRouter();

// 旅游规划表单数据
const formData = reactive({
  city: "",
  budget: null,
  days: null,
});
const popularCities = ["北京", "上海", "杭州", "成都"];
const showCityPicker = ref(false),
  cityQuery = ref(""),
  cityCode = ref("");
const cityMatches = computed(() => searchDestinations(cityQuery.value));
const openCityPicker = () => {
  cityQuery.value = "";
  cityCode.value = findDestination(formData.city)?.code || "";
  showCityPicker.value = true;
};
const chooseDestination = (code) => {
  const city = destinationByCode(code);
  if (!city) return;
  formData.city = city.fullName;
  showCityPicker.value = false;
};

// 加载状态
const isLoading = ref(false);

// 提交表单
const handleSubmit = async () => {
  const error = validateTrip(formData);
  if (error) {
    showToast(error);
    return;
  }
  isLoading.value = true;
  try {
    await router.push({
      path: "/detail",
      query: {
        city: formData.city,
        budget: formData.budget,
        days: formData.days,
      },
    });
  } finally {
    isLoading.value = false;
  }
};

// 跳转页面
const goPage = (path) => {
  router.push(path);
};

// 选择热门目的地
const selectedCity = (city) => {
  formData.city = city;
};
</script>
<style scoped>
.agent-entry { display:flex; align-items:center; justify-content:space-between; gap:16px; margin-top:18px; padding:16px 18px; border:1px solid #d9e6dc; border-radius:12px; background:#f4f8f4; color:#35634a; text-decoration:none; }
.agent-entry strong { display:block; font-size:14px; }
.agent-entry small { display:block; margin-top:5px; color:#6e8275; font-size:12px; }
.home-content { padding-top: 24px; }
.home-hero { display: flex; align-items: center; justify-content: space-between; gap: 24px; min-height: 164px; margin-bottom: 18px; }
.hero-copy { flex: 1; }
.hero-eyebrow { display: flex; align-items: center; gap: 8px; color: #71849a; font-size: 12px; letter-spacing: 1px; }
.hero-eyebrow > span { width: 6px; height: 6px; border-radius: 50%; background: #e4a557; }
.hero-copy h1 { font-size: clamp(30px, 3.3vw, 40px); line-height: 1.4; letter-spacing: -1.3px; margin: 14px 0 12px; font-weight: 750; }
.hero-copy h1 span { color: var(--primary); }
.hero-copy > p { color: var(--muted); font-size: 14px; margin: 0; line-height: 1.9; }
.hero-features { display: flex; gap: 20px; color: #6d8096; font-size: 12px; margin-top: 16px; }
.hero-features span { display: flex; align-items: center; gap: 5px; }
.hero-features .van-icon { color: #668ac5; font-size: 16px; }
.mobile-break { display: none; }
.travel-art { width: 300px; height: 164px; position: relative; flex-shrink: 0; }
.travel-art svg { width: 100%; height: 100%; }
.art-ticket { position: absolute; bottom: 0; left: 55px; display: flex; gap: 12px; align-items: center; padding: 13px 18px; border: 1px solid #e2eaf5; background: #ffffffed; border-radius: 12px; box-shadow: 0 8px 24px #42638b09; transform: rotate(-5deg); }
.art-ticket > .van-icon { font-size: 26px; color: var(--primary); }
.art-ticket span { color: #8a9aab; font-size: 8px; letter-spacing: 1.2px; }
.art-ticket strong { display: block; color: #385271; font-size: 12px; letter-spacing: 1px; margin-top: 3px; }
.art-ticket i { color: #dda257; font-size: 22px; margin-left: 5px; }
.home-workspace { display: grid; grid-template-columns: minmax(0, 1.24fr) minmax(0, 1fr); gap: 24px; align-items: stretch; }
.search-card { padding: 24px; margin: 0; height: 100%; }
.planner-heading { display: flex; gap: 12px; align-items: center; margin-bottom: 20px; }
.planner-heading h2 { margin: 0 0 3px; font-size: 20px; }
.planner-heading p { margin: 0; font-size: 12px; color: var(--muted); }
.section-icon { width: 42px; height: 42px; display: grid; place-items: center; border-radius: 12px; background: #edf3ff; color: var(--primary); font-size: 23px; flex-shrink: 0; }
.step-badge { margin-left: auto; align-self: flex-start; color: #8a99ad; font-size: 10px; white-space: nowrap; padding-top: 2px; }
.planner-row { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; }
.planner-field { display: flex; flex-direction: column; gap: 8px; padding: 0; background: transparent; margin-bottom: 16px; overflow: visible; }
.planner-field:after { display: none; }
.planner-field :deep(.van-field__label) { width: auto; margin: 0; color: #485e76; font-size: 13px; }
.planner-field :deep(.van-field__body) { min-height: 50px; padding: 7px 12px; border: 1px solid var(--line); border-radius: 10px; background: #f8fafd; transition: border-color .15s, box-shadow .15s; }
.planner-field :deep(.van-field__body:focus-within) { border-color: #8caff4; background: white; box-shadow: 0 0 0 3px #2763dc0a; }
.planner-field :deep(input) { font-size: 14px; }
.planner-field :deep(.van-field__button) { padding-left: 6px; }
.planner-field :deep(.van-button) { font-size: 12px; padding: 0 9px; height: 32px; border: none; background: #edf3ff; }
.planner-field :deep(.van-button .van-icon) { font-size: 11px; margin-left: 4px; }
.plan-submit { font-size: 15px; margin-top: 2px; }
.plan-submit .van-icon { margin-left: 12px; font-size: 13px; }
.planner-notice { font-size: 11px; line-height: 1.8; color: #8492a5; text-align: center; margin: 16px 0 0; }
.planner-notice .van-icon { margin-right: 3px; }
.destination-heading { display: flex; justify-content: space-between; align-items: center; gap: 8px; margin-bottom: 16px; }
.destination-heading h2 { margin: 0; font-size: 16px; }
.text-link { border: none; color: var(--primary); font-size: 12px; padding: 4px 0; background: transparent; white-space: nowrap; }
.text-link .van-icon { font-size: 10px; }
.destination-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.city-tile { min-width: 0; display: flex; align-items: center; gap: 9px; border: 1px solid #edf1f6; border-radius: 11px; padding: 12px 10px; text-align: left; background: #f8fafc; color: var(--ink); transition: border-color .15s, background .15s; }
.city-symbol { font-size: 20px; color: #7699be; }
.city-tile strong { font-size: 14px; display: block; font-weight: 600; }
.city-tile small { font-size: 10px; color: #8a9aab; display: block; margin-top: 2px; }
.city-tile > .van-icon { font-size: 10px; color: #a3b0c0; margin-left: auto; }
.city-tile:hover, .city-tile.active { border-color: #a8c2f5; background: #eff5ff; }
.city-tile.active strong, .city-tile.active .city-symbol { color: var(--primary); }
.popular-destinations { margin-bottom: 18px; }
.quick-actions { margin-bottom: 0; padding-top: 20px; padding-bottom: 15px; }
.quick-actions h2 { font-size: 14px; margin-bottom: 10px; }
.quick-links button { display: flex; align-items: center; width: 100%; gap: 12px; padding: 9px 0; background: transparent; border: none; color: var(--ink); text-align: left; }
.quick-links button + button { border-top: 1px solid #f0f3f8; }
.quick-icon { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 10px; font-size: 18px; color: #4b77ca; background: #edf3ff; flex-shrink: 0; }
.quick-icon.mint { background: #eaf6f2; color: #4d9887; }
.quick-links strong { font-weight: 550; font-size: 13px; display: block; }
.quick-links small { font-size: 10px; color: #8696a8; display: block; margin-top: 2px; }
.quick-links button > .van-icon { margin-left: auto; color: #a0afbf; font-size: 12px; }
.home-footnote { display: flex; justify-content: space-between; color: #9ba8b8; font-size: 11px; padding: 24px 2px 0; }
.home-footnote span { letter-spacing: 2px; font-size: 9px; }
.city-popup { max-height: 85dvh; }
.city-picker { padding: 8px 12px calc(12px + env(safe-area-inset-bottom)); background: white; }
.city-picker-header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 12px 10px; }
.city-picker-header p { margin: 0 0 2px; color: #8a9aab; font-size: 11px; }
.city-picker-header h2 { font-size: 22px; color: var(--ink); margin: 0; }
.city-picker-note, .city-search-count { font-size: 12px; color: var(--muted); line-height: 1.8; margin: 6px 12px 12px; }
.city-search-results { max-height: 48dvh; min-height: 180px; overflow-y: auto; }
.city-picker :deep(.van-cascader__options) { height: min(38dvh, 300px); }
.city-picker :deep(.van-cascader__tab) { max-width: min(55vw, 280px); }
.city-picker :deep(.van-search__content) { border-radius: 10px; background: #f3f6fa; }
@media (min-width: 900px) { .home-mobile-header { display: none; } }
@media (max-width: 899px) {
  .home-workspace { gap: 18px; grid-template-columns: minmax(0, 1fr); }
  .home-support { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; align-items: start; }
  .popular-destinations { margin-bottom: 0; }
  .home-hero { min-height: 180px; }
  .travel-art { width: 240px; height: 180px; }
  .art-ticket { left: 16px; bottom: -4px; }
  .hero-copy h1 { font-size: 32px; }
  .step-badge { font-size: 11px; }
}
@media (max-width: 599px) {
  .home-content { padding-top: 24px; }
  .home-hero { margin-bottom: 24px; min-height: auto; }
  .hero-eyebrow { font-size: 11px; letter-spacing: .5px; }
  .hero-copy h1 { font-size: 29px; letter-spacing: -1px; margin: 12px 0; }
  .hero-copy > p { font-size: 12px; }
  .hero-features { gap: 16px; margin-top: 16px; font-size: 11px; }
  .travel-art { display: none; }
  .mobile-break { display: block; }
  .search-card { padding: 20px; }
  .planner-heading { gap: 10px; margin-bottom: 22px; }
  .planner-heading h2 { font-size: 18px; }
  .planner-heading p { font-size: 11px; }
  .step-badge { display: none; }
  .planner-row { gap: 12px; }
  .planner-field :deep(input) { font-size: 13px; }
  .planner-field :deep(.van-field__body) { padding-left: 10px; padding-right: 8px; }
  .planner-field :deep(.van-button) { font-size: 11px; padding: 0 6px; }
  .planner-notice { font-size: 10px; text-align: left; }
  .home-support { display: block; }
  .popular-destinations { margin-bottom: 14px; }
  .home-footnote { display: block; text-align: center; padding-top: 22px; }
  .home-footnote span { display: block; margin-bottom: 4px; }
}
</style>
