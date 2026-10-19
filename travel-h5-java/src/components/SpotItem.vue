<template>
  <div class="spot-item" v-if="data">
    <div class="spot-name">{{ data.spot || data.name || "待定" }}</div>
    <div
      class="spot-details"
      v-if="data.duration || data.ticket || data.transportation"
    >
      <div class="detail-row" v-if="data.duration">
        <van-icon name="clock-o" size="14" />
        <span>{{ data.duration }}</span>
      </div>
      <div class="detail-row" v-if="data.ticket">
        <van-icon name="ticket-o" size="14" />
        <span>{{ data.ticket }}</span>
      </div>
      <div class="detail-row" v-if="data.transportation">
        <van-icon name="logistics" size="14" />
        <span>{{ data.transportation }}</span>
      </div>
    </div>
    <div v-if="verified && data.placeId" class="place-source">
      <span v-if="data.indoor">室内类别 · </span
      ><a :href="data.sourceUrl" target="_blank" rel="noopener noreferrer"
        >OpenStreetMap 地点来源</a
      >
      <div>
        查询于 {{ new Date(data.queriedAt).toLocaleString("zh-CN") }} ·
        票价/开放条件未核实
      </div>
      <div v-if="data.route">
        路线来源 {{ data.route.source }} · 查询于
        {{ new Date(data.route.queriedAt).toLocaleString("zh-CN") }}
      </div>
    </div>
    <div class="spot-desc" v-if="data.description">{{ data.description }}</div>
  </div>
  <div class="spot-item empty" v-else>
    <van-empty description="暂无安排" :image-size="40" />
  </div>
</template>

<script setup>
defineProps({
  verified: { type: Boolean, default: false },
  data: {
    type: Object,
    default: () => ({}),
  },
});
</script>

<style scoped>
.spot-item {
  padding: 8px 0;
}

.spot-item.empty {
  padding: 16px 0;
}

.spot-name {
  font-size: 16px;
  font-weight: 600;
  color: #323233;
  margin-bottom: 8px;
}

.spot-details {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 8px;
}

.detail-row {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #666;
}

.spot-desc {
  font-size: 14px;
  color: #738397;
  line-height: 1.85;
  overflow-wrap: anywhere;
}
.place-source {
  font-size: 12px;
  line-height: 1.7;
  color: #758490;
  background: #f1f6fa;
  padding: 8px 10px;
  border-radius: 7px;
  margin: 8px 0;
}
.place-source a {
  color: #256b9d;
}
</style>
