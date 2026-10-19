<template>
  <section class="message-evidence" :class="{ grounded: evidence.mode === 'KNOWLEDGE' }" :aria-label="chatEvidenceLabel(evidence.mode)">
    <div class="evidence-label"><van-icon :name="evidence.mode === 'KNOWLEDGE' ? 'passed' : 'info-o'" /><strong>{{ chatEvidenceLabel(evidence.mode) }}</strong><span v-if="evidence.city">{{ evidence.city }}</span></div>
    <p>{{ evidence.notice }}</p>
    <details v-for="source in evidence.sources" :key="source.chunkId"><summary>{{ source.ref }} · {{ source.title }}</summary>
      <blockquote v-for="(quote,index) in quotes(source.ref)" :key="index">{{ quote }}</blockquote>
      <small>资料日期 {{ source.publishedOn || '未标明' }} · 入库 {{ new Date(source.importedAt).toLocaleDateString('zh-CN') }} · {{ source.origin === 'OFFICIAL_DIGEST' ? '项目整理摘要' : '用户导入资料' }}</small>
      <a v-if="safeSource(source.sourceUrl)" :href="safeSource(source.sourceUrl)" target="_blank" rel="noopener noreferrer">查看原始来源 ↗</a>
      <details><summary>查看引用片段</summary><p class="source-content">{{ source.content }}</p></details>
    </details>
  </section>
</template>
<script setup lang="ts">
import type { ChatEvidence } from '../utils/knowledge';
import { chatEvidenceLabel } from '../utils/chatEvidence.js';
const props=defineProps<{evidence:ChatEvidence}>();
function quotes(ref:string){return [...new Set(props.evidence.claims.flatMap(c=>c.citations.filter(c=>c.ref===ref).map(c=>c.quote)))];}
function safeSource(value:string){try{const url=new URL(value);return url.protocol==='https:'&&!url.username&&!url.password?url.href:undefined;}catch{return undefined;}}
</script>
<style scoped>
.message-evidence { max-width:86%; padding: 12px 14px; border:1px solid #e5eaf2; background:#f8fafc; border-radius:10px; color:#78899d; font-size:11px; overflow-wrap:anywhere; }
.message-evidence.grounded { background:#f0f6ff; border-color:#dae7fb; color:#617fa6; }
.evidence-label { display:flex;gap:6px;align-items:center;flex-wrap:wrap; }
.evidence-label strong {font-size:12px;font-weight:600;}
.evidence-label span {margin-left:auto;}
.message-evidence p { margin:6px 0 0;line-height:1.8; }
.message-evidence > details {border-top:1px solid #dce7f5;margin-top:10px;padding-top:9px;}
summary {cursor:pointer;font-size:12px;line-height:1.7;}
blockquote {margin:10px 0;padding:8px 12px;border-left:3px solid #82a7e3;background:white;color:#4d6786;font-size:12px;line-height:1.8;white-space:pre-wrap;}
small {display:block;font-size:10px;line-height:1.8;}
a {display:inline-block;margin:8px 0;}
.source-content {white-space:pre-wrap;}
@media(max-width:599px){.message-evidence{max-width:94%;}}
</style>
