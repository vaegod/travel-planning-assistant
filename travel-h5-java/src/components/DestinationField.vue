<template>
  <div class="destination-control">
    <van-field v-model="value" :label="label" :placeholder="placeholder || (autoContext ? '自动识别问题及会话中的目的地' : allowAll ? '留空检索全部城市资料' : '填写目的地，或选择全国城市')" maxlength="50" :clearable="allowAll" :disabled="disabled">
      <template #button><van-button size="small" plain type="primary" :disabled="disabled" @click="open">选择城市</van-button></template>
    </van-field>
    <van-popup v-model:show="show" position="bottom" round>
      <section class="destination-sheet" role="dialog" aria-modal="true" aria-label="全国城市选择">
        <header><h2>选择目的地</h2><van-button size="small" @click="show = false">取消</van-button></header>
        <van-search v-model="query" placeholder="搜索城市或省份，例如：西安" aria-label="搜索城市或省份" />
        <p>支持全国目的地；未列出的区县或景区可以手动填写。{{ autoContext ? '留空时优先使用问题或当前会话中的城市。' : allowAll ? '清空城市后检索本账号全部资料。' : '' }}</p>
        <template v-if="query.trim()"><div class="destination-results"><van-cell v-for="city in matches" :key="city.code" :title="city.name" :label="city.provinceName" is-link clickable @click="choose(city.code)" /><van-empty v-if="!matches.length" description="没有匹配项，可返回后手动填写" /></div></template>
        <van-cascader v-else v-model="code" :options="cityOptions" :show-header="false" active-color="var(--primary)" @finish="({ value }) => choose(String(value))" />
      </section>
    </van-popup>
  </div>
</template>
<script setup lang="ts">
import { ref, computed } from 'vue';
import { cityOptions, findDestination, destinationByCode, searchDestinations } from '../utils/cityCatalog';
const value = defineModel<string>({default: ''});
withDefaults(defineProps<{label?:string;placeholder?:string;allowAll?:boolean;autoContext?:boolean;disabled?:boolean}>(),{label:'资料城市',placeholder:'',allowAll:false,autoContext:false,disabled:false});
const show=ref(false), query=ref(''), code=ref('');
const matches=computed(()=>searchDestinations(query.value));
function open(){query.value='';code.value=findDestination(value.value)?.code||'';show.value=true;}
function choose(code:string){const city=destinationByCode(code);if(city){value.value=city.fullName;show.value=false;}}
</script>
<style scoped>
.destination-control { min-width: 0; margin: 14px 0; }
.destination-control > .van-field { padding: 0; display: flex; flex-direction: column; gap: 8px; overflow: visible; background: transparent; }
.destination-control > .van-field:after { display: none; }
.destination-control :deep(.van-field__label) { width: auto; margin: 0; font-size: 13px; color: #526a83; }
.destination-control :deep(.van-field__body) { padding: 6px 10px; min-height: 46px; background: #f7f9fc; border: 1px solid var(--line); border-radius: 9px; }
.destination-control :deep(.van-field__body:focus-within) { border-color: #8caff4; }
.destination-control :deep(.van-field__control) { font-size: 13px; }
.destination-control :deep(.van-field__button .van-button) { font-size: 11px; padding: 0 8px; height: 30px; }
.destination-sheet { padding: 22px 12px calc(12px + env(safe-area-inset-bottom)); }
.destination-sheet header { display: flex; justify-content: space-between; align-items: center; padding: 0 12px 10px; }
.destination-sheet h2 { font-size: 21px; margin: 0; }
.destination-sheet p { color: var(--muted); font-size: 12px; line-height: 1.8; margin: 10px 12px; }
.destination-results { max-height: 44dvh; overflow: auto; min-height: 160px; }
.destination-sheet :deep(.van-cascader__options) { height: min(40dvh,300px); }
.destination-sheet :deep(.van-cascader__tab) { max-width: min(55vw,280px); }
</style>
