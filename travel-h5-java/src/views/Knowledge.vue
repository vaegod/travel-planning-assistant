<template>
  <main class="page-container kb-page">
    <div class="page-header">
      <van-nav-bar
        title="旅游知识库"
        left-arrow
        left-text="返回"
        @click-left="router.push('/')"
      />
    </div>
    <div class="page-content kb-content">
      <header class="card kb-overview">
        <div class="kb-overview-title">
          <van-icon name="notes-o" class="kb-overview-icon" />
          <h1 class="section-title">旅游资料与问答</h1>
          <span class="kb-count">{{ documents.length }} 份资料</span>
        </div>
        <p>可选资料扩展：导入、检索和独立资料问答。</p>
        <p class="kb-small">资料仅当前账号可见。此扩展与普通 AI 对话、首页行程规划相互独立，不自动参与回答。</p>
        <van-button size="small" plain type="primary" @click="router.push('/chat')">前往融合 AI 问答</van-button>
      </header>
      <van-tabs
        v-model:active="tab"
        class="kb-tabs"
        color="var(--primary)"
        title-active-color="var(--primary)"
        :ellipsis="false"
        aria-label="知识库功能"
      >
        <van-tab
          v-for="item in tabs"
          :key="item.key"
          :name="item.key"
          :title="item.label"
        />
      </van-tabs>
      <div v-if="error" role="alert" class="account-error">{{ error }}</div>
      <div v-if="notice" role="status" class="kb-note">{{ notice }}</div>
      <template v-if="tab === 'documents'">
        <section class="card kb-panel pack-panel">
          <h2 class="section-title">导入自己的 JSON 资料包</h2>
          <p>项目不再附带公开旅游资料或内置摘要。可选择自己准备且有权使用的资料包；单份文本导入功能也继续保留。</p>
          <p v-if="packError" class="account-error">{{ packError }}</p>
          <label class="kb-file">选择自己的 JSON 资料包<input type="file" accept=".json,application/json" aria-label="选择JSON资料包" :disabled="importing" @change="readPackFile" /></label>
          <div v-if="batchPack" class="pack-preview"><strong>{{ batchPack.title }}</strong><p>{{ batchPack.documents.length }}份资料 · {{ new Set(batchPack.documents.map(d=>d.city)).size }}个目的地 · {{ batchPack.license }}</p><p class="kb-small">{{ batchPack.notice }}</p><details><summary>预览资料标题与城市</summary><ul><li v-for="(doc,index) in batchPack.documents" :key="index">{{ doc.city }} · {{ doc.title }}</li></ul></details><p class="kb-small">导入时调用已配置的向量服务。逐份保存、自动去重；可以暂停，重复导入同一文件可继续补齐。</p><div class="pack-actions"><van-button type="primary" :disabled="importing" @click="importBatch">导入资料包</van-button><van-button v-if="batchRunning" @click="batchStopped = true">完成当前资料后暂停</van-button></div><p role="status" v-if="batchProgress">{{ batchProgress }}</p></div>
        </section>
        <details class="card kb-panel import-panel">
          <summary>导入自己的 TXT / Markdown 资料</summary>
          <p class="kb-small">
            填写原页链接并粘贴正文，或选择本地文本。导入会将文本发送到已配置的向量服务；单份最多12000字符。
          </p>
          <label class="kb-file"
            >选择文本文件<input
              type="file"
              accept=".txt,.md,text/plain,text/markdown"
              aria-label="选择资料文件"
              @change="readFile" /></label
          ><label
            >资料标题<input
              v-model="form.title"
              maxlength="160"
              placeholder="例如：我的杭州文化参访笔记"
          /></label>
          <div class="kb-two">
            <DestinationField v-model="form.city" label="资料城市" /><label
              >原页发布日期（可空）<input
                v-model="form.publishedOn"
                type="date"
            /></label>
          </div>
          <label
            >原页 HTTPS 链接（本地笔记可空）<input
              v-model="form.sourceUrl"
              placeholder="https://..."
          /></label>
          <div class="kb-two">
            <label
              >有效期起（可空）<input
                v-model="form.validFrom"
                type="date" /></label
            ><label
              >有效期止（可空）<input v-model="form.validTo" type="date"
            /></label>
          </div>
          <label
            >资料正文<textarea
              v-model="form.content"
              rows="8"
              maxlength="12000"
              placeholder="粘贴资料文本，不会自动抓取链接中的网页。"
            /></label
          ><van-button
            block
            round
            type="primary"
            :loading="importing"
            :disabled="importing"
            @click="importText"
            >导入并建立索引</van-button
          >
        </details>
        <div class="kb-list-title">
          <h2 class="section-title">我的资料</h2>
          <span
            >{{ documents.reduce((n, d) => n + d.chunkCount, 0) }} 个片段</span
          >
        </div>
        <van-empty
          v-if="!loading && !documents.length"
          description="还没有资料，从上方导入开始。"
        /><van-loading v-if="loading" />
        <article
          v-for="doc in documents"
          :key="doc.id"
          class="card kb-document"
        >
          <span class="kb-city"
            >{{ doc.city }} /
            {{
              doc.origin === "OFFICIAL_DIGEST" ? "官方页整理摘要" : "用户资料"
            }}</span
          >
          <h3>{{ doc.title }}</h3>
          <p>
            原页日期 {{ doc.publishedOn || "未标明" }} ·
            {{ doc.chunkCount }}个片段
          </p>
          <div class="kb-actions">
            <button @click="inspect(doc.id)">查看正文与片段 ↗</button
            ><button class="kb-delete" @click="remove(doc.id)">删除资料</button>
          </div>
        </article>
      </template>
      <template v-else>
        <section class="card kb-panel query-panel">
          <h2 class="section-title">
            {{ tab === "search" ? "检索旅行资料" : "依据资料回答" }}
          </h2>
          <div class="kb-two">
            <DestinationField v-model="city" label="检索城市（可空）" allow-all /><label
              >资料有效日期（默认今天）<input v-model="asOf" type="date"
            /></label>
          </div>
          <label
            >你的问题<textarea
              v-model="question"
              rows="3"
              maxlength="2000"
              placeholder="例如：中国茶叶博物馆的两个馆区分别在哪里？"
            />
          </label>
          <p class="kb-small">
            按城市与资料有效期筛选；有效期留空的资料没有明确时效保证。每个问题独立检索。今天开放、实时票价或余票应查看官方最新公告。
          </p>
          <van-button
            block
            round
            type="primary"
            :loading="asking"
            :disabled="asking || pending"
            @click="submit"
            >{{ tab === "search" ? "检索资料" : "检索并回答" }}</van-button
          >
        </section>
        <div v-if="pending" class="card kb-panel kb-progress" role="status">
          <van-loading size="18" />检索资料与核对引用中<van-button
            size="small"
            @click="cancel"
            >取消</van-button
          >
        </div>
        <section v-if="tab === 'search' && searched" class="card kb-panel">
          <h2 class="section-title">相关片段 · {{ matches.length }}</h2>
          <p class="kb-small">相似度是检索排序分数，不是答案置信度。</p>
          <van-empty
            v-if="!matches.length"
            description="未检索到符合筛选和相似度阈值的片段"
          />
          <article
            v-for="source in matches"
            :key="source.chunkId"
            class="kb-source"
          >
            <span class="kb-ref">{{ source.ref }}</span>
            <h3>{{ source.title }}</h3>
            <p>{{ source.content }}</p>
            <p class="kb-small">
              片段{{ source.ordinal }} · 相似度{{ source.score.toFixed(3) }} ·
              原页日期{{ source.publishedOn || "未标明" }}
            </p>
            <a
              v-if="source.sourceUrl"
              :href="source.sourceUrl"
              target="_blank"
              rel="noopener noreferrer"
              >打开原页 ↗</a
            ><button @click="inspect(source.documentId)">核对本地全文</button>
          </article>
        </section>
        <section
          v-if="tab === 'ask' && answer"
          class="card kb-panel answer-panel"
        >
          <span class="kb-index">{{
            answer.status === "READY"
              ? "有来源的回答"
              : answer.status === "ABSTAINED"
                ? "依据不足，暂不作答"
                : answer.status === "ERROR"
                  ? "本次回答未完成"
                  : "问答记录"
          }}</span>
          <h2 class="section-title">{{ answer.question }}</h2>
          <p v-if="answer.status === 'ERROR'" role="alert">
            {{ answer.message }}
          </p>
          <p v-if="answer.status === 'CANCELLED'">
            已取消，可修改问题后重新提问。
          </p>
          <template v-if="answer.answer"
            ><p v-if="answer.answer.abstained" class="kb-abstain">
              {{ answer.answer.reason }}
            </p>
            <article
              v-for="(claim, index) in answer.answer.claims"
              :key="index"
              class="kb-claim"
            >
              <p>{{ claim.text }}</p>
              <details v-for="(citation, c) in claim.citations" :key="c">
                <summary>
                  <span class="kb-ref">{{ citation.ref }}</span> 核对引用原文
                </summary>
                <blockquote>{{ citation.quote }}</blockquote>
              </details>
            </article>
            <p class="kb-small">{{ answer.answer.notice }}</p>
            <details
              v-for="source in answer.answer.sources"
              :key="source.chunkId"
              class="kb-source"
            >
              <summary>
                {{ source.ref }} · {{ source.title }} / 片段{{ source.ordinal }}
              </summary>
              <p>{{ source.content }}</p>
              <p class="kb-small">
                原页日期{{ source.publishedOn || "未标明" }} · 本地入库{{
                  formatDate(source.importedAt)
                }}
              </p>
              <a
                v-if="source.sourceUrl"
                :href="source.sourceUrl"
                target="_blank"
                rel="noopener noreferrer"
                >原始来源 ↗</a
              ><button @click="inspect(source.documentId)">查看本地全文</button>
            </details></template
          >
        </section>
        <section v-if="tab === 'ask'" class="card kb-panel">
          <h2 class="section-title">最近的资料问答</h2>
          <p class="kb-small">
            刷新后可继续查看；已完成答案保留当时的引用快照。
          </p>
          <div v-for="item in history" :key="item.id" class="kb-history">
            <button @click="openAnswer(item.id)">
              {{ item.question
              }}<small
                >{{ statusName(item.status) }} · {{ item.city }}</small
              ></button
            ><button aria-label="删除问答记录" @click="deleteAnswer(item.id)">
              删除
            </button>
          </div>
        </section>
      </template>
    </div>
    <van-popup
      v-model:show="showDocument"
      position="bottom"
      round
      class="source-popup"
      ><div v-if="detail" class="kb-document-view">
        <button class="kb-close" @click="showDocument = false">关闭</button
        ><span class="kb-index">资料详情</span>
        <h2 class="section-title">{{ detail.document.title }}</h2>
        <a
          v-if="detail.document.sourceUrl"
          :href="detail.document.sourceUrl"
          target="_blank"
          rel="noopener noreferrer"
          >查看原始来源 ↗</a
        >
        <p class="kb-small">
          原页日期{{ detail.document.publishedOn || "未标明" }} · 入库{{
            formatDate(detail.document.createdAt)
          }}
        </p>
        <h3>完整正文</h3>
        <pre>{{ detail.content }}</pre>
        <h3>切分片段与定位</h3>
        <article
          v-for="piece in detail.chunks"
          :key="piece.id"
          class="kb-source"
        >
          <strong
            >片段{{ piece.ordinal }} · 字符区间[{{ piece.startOffset }},{{
              piece.endOffset
            }})</strong
          >
          <p>{{ piece.content }}</p>
        </article>
      </div></van-popup
    >
  </main>
</template>
<script setup lang="ts">
import DestinationField from '../components/DestinationField.vue';
import { decodeKnowledgePack, type KnowledgePack } from '../utils/knowledgePack';
import { ref, reactive, computed, onMounted, onBeforeUnmount } from "vue";
import { useRouter } from "vue-router";
import { showConfirmDialog } from "vant";
import {
  knowledgeApi,
  type KnowledgeDoc,
  type KnowledgeDetail,
  type KnowledgeMatch,
  type RagQuestion,
} from "../utils/knowledge";
import { messageOf } from "../utils/http";
import { decodeKnowledgeFile } from "../utils/knowledgeFile";
const router = useRouter(),
  tabs = [
    { key: "documents", label: "资料库" },
    { key: "search", label: "检索" },
    { key: "ask", label: "资料问答" },
  ];
const tab = ref("documents"),
  documents = ref<KnowledgeDoc[]>([]),
  history = ref<RagQuestion[]>([]),
  detail = ref<KnowledgeDetail | null>(null),
  showDocument = ref(false),
  loading = ref(false),
  importing = ref(false),
  asking = ref(false),
  error = ref(""),
  notice = ref(""),
  city = ref(""),
  asOf = ref(""),
  question = ref(""),
  matches = ref<KnowledgeMatch[]>([]),
  searched = ref(false),
  answer = ref<RagQuestion | null>(null);
const form = reactive({
  title: "",
  city: "",
  sourceUrl: "",
  publishedOn: "",
  validFrom: "",
  validTo: "",
  content: "",
});

const packError=ref(''), batchPack=ref<KnowledgePack|null>(null),
  batchProgress=ref(''), batchRunning=ref(false), batchStopped=ref(false);
async function readPackFile(event:Event){try{const f=(event.target as HTMLInputElement).files?.[0];if(!f)return;if(f.size>16*1024*1024)throw Error('请选择16MB以内的JSON资料包');batchPack.value=decodeKnowledgePack(f.name,new Uint8Array(await f.arrayBuffer()));batchProgress.value='';packError.value='';}catch(e){packError.value=messageOf(e);}finally{(event.target as HTMLInputElement).value='';}}
async function importBatch(){if(!batchPack.value||importing.value)return;const docs=batchPack.value.documents;importing.value=true;batchRunning.value=true;batchStopped.value=false;error.value='';let added=0,duplicate=0,completed=0;try{for(const doc of docs){if(batchStopped.value||disposed)break;batchProgress.value=`正在处理 ${completed+1}/${docs.length}：${doc.title}`;const result=await knowledgeApi.import(doc);if(result.duplicate)duplicate++;else added++;completed++;}batchProgress.value=`${completed===docs.length?'导入完成':'已暂停'}：处理${completed}/${docs.length}份，新增${added}份，已存在${duplicate}份。`;}catch(e){batchProgress.value=`处理${completed}/${docs.length}份后停止，新增${added}份，已存在${duplicate}份；已成功的资料保留，重复导入可继续。`;error.value=messageOf(e);}finally{importing.value=false;batchRunning.value=false;if(!disposed)await load();}}

const pending = computed(
  () => answer.value?.status === "QUEUED" || answer.value?.status === "RUNNING",
);
let timer: ReturnType<typeof setTimeout> | undefined,
  generation = 0,
  disposed = false;
const formatDate = (value: string) => new Date(value).toLocaleString("zh-CN");
const statusName = (value: string) =>
  ({
    READY: "有来源",
    ABSTAINED: "依据不足",
    ERROR: "失败",
    CANCELLED: "取消",
    QUEUED: "等待中",
    RUNNING: "处理中",
  })[value] || value;
const load = async () => {
  loading.value = true;
  try {
    [documents.value, history.value] = await Promise.all([
      knowledgeApi.documents(),
      knowledgeApi.history(),
    ]);
  } catch (e) {
    error.value = messageOf(e);
  } finally {
    loading.value = false;
  }
};
const readFile = async (event: Event) => {
  try {
    const file = (event.target as HTMLInputElement).files?.[0];
    if (!file) return;
    if (file.size > 100000) throw Error("请选择100KB以内的TXT或Markdown文本");
    form.content = decodeKnowledgeFile(
      file.name,
      new Uint8Array(await file.arrayBuffer()),
    );
    if (!form.title) form.title = file.name.replace(/\.[^.]+$/, "");
  } catch (e) {
    error.value = messageOf(e);
  }
};
const importText = async () => {
  importing.value = true;
  error.value = "";
  try {
    const result = await knowledgeApi.import({
      ...form,
      publishedOn: form.publishedOn || null,
      validFrom: form.validFrom || null,
      validTo: form.validTo || null,
    });
    notice.value = result.duplicate
      ? "相同资料已存在，没有重复建立索引。"
      : "资料已导入并建立索引。";
    await load();
  } catch (e) {
    error.value = messageOf(e);
  } finally {
    importing.value = false;
  }
};
const inspect = async (id: string) => {
  try {
    detail.value = await knowledgeApi.detail(id);
    showDocument.value = true;
  } catch (e) {
    error.value = messageOf(e);
  }
};
const remove = async (id: string) => {
  try {
    await showConfirmDialog({
      title: "删除资料",
      message:
        "删除后不再参与检索。已有问答保留当时的引用快照，可另行删除问答记录。",
    });
    await knowledgeApi.delete(id);
    await load();
  } catch (e) {
    if (e === "cancel" || e === "close") return;
    error.value = messageOf(e);
  }
};
const poll = async (id: string, current: number) => {
  try {
    const value = await knowledgeApi.answer(id);
    if (disposed || current !== generation) return;
    answer.value = value;
    if (value.status === "QUEUED" || value.status === "RUNNING")
      timer = setTimeout(() => void poll(id, current), 800);
    else history.value = await knowledgeApi.history();
  } catch (e) {
    if (!disposed && current === generation) error.value = messageOf(e);
  }
};
const stopPolling = () => {
  generation++;
  if (timer) clearTimeout(timer);
};
const openAnswer = async (id: string) => {
  stopPolling();
  error.value = "";
  tab.value = "ask";
  await poll(id, generation);
};
const submit = async () => {
  if (!question.value.trim()) {
    error.value = "请先填写问题";
    return;
  }
  asking.value = true;
  error.value = "";
  notice.value = "";
  try {
    if (tab.value === "search") {
      const result = await knowledgeApi.search(
        question.value,
        city.value,
        asOf.value,
      );
      matches.value = result.matches;
      searched.value = true;
    } else {
      stopPolling();
      answer.value = await knowledgeApi.ask(
        question.value,
        city.value,
        asOf.value,
      );
      void poll(answer.value.id, generation);
    }
  } catch (e) {
    error.value = messageOf(e);
  } finally {
    asking.value = false;
  }
};
const cancel = async () => {
  if (!answer.value) return;
  const id = answer.value.id;
  stopPolling();
  try {
    await knowledgeApi.cancel(id);
    await poll(id, generation);
  } catch (e) {
    error.value = messageOf(e);
  }
};
const deleteAnswer = async (id: string) => {
  try {
    await showConfirmDialog({
      title: "删除问答",
      message: "将删除这一条资料问答及其引用快照。",
    });
    await knowledgeApi.deleteAnswer(id);
    if (answer.value?.id === id) {
      stopPolling();
      answer.value = null;
    }
    history.value = await knowledgeApi.history();
  } catch (e) {
    if (e === "cancel" || e === "close") return;
    error.value = messageOf(e);
  }
};
onMounted(() => { void load(); });
onBeforeUnmount(() => {
  disposed = true;
  batchStopped.value = true;
  stopPolling();
  if (pending.value && answer.value)
    void knowledgeApi.cancel(answer.value.id).catch(() => {});
});
</script>
<style scoped>
.kb-page {
  color: #323233;
}
.kb-overview-title {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.kb-overview-title h1 {
  margin: 0;
  line-height: 1.5;
}
.kb-overview-icon {
  color: var(--primary);
  font-size: 22px;
}
.kb-count {
  margin-left: auto;
  padding: 4px 10px;
  color: var(--primary);
  background: #edf6ff;
  border-radius: 16px;
  font-size: 12px;
}
.kb-overview > p,
.kb-panel > p {
  font-size: 14px;
  line-height: 1.7;
  color: #646566;
  margin: 10px 0;
}
.kb-overview > p:last-child {
  margin-bottom: 0;
}
.kb-tabs {
  margin-bottom: 16px;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
}
.kb-panel h2 {
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.kb-panel :deep(.van-button--block) {
  margin-top: 16px;
}
.kb-small {
  font-size: 12px !important;
  color: #74859a !important;
  line-height: 1.7;
}
.kb-index {
  display: inline-block;
  margin-bottom: 8px;
  color: var(--primary);
  font-size: 12px;
}
.kb-list-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 20px 0 12px;
}
.kb-list-title h2 {
  margin: 0;
}
.kb-list-title span {
  color: #74859a;
  font-size: 12px;
}
.kb-city {
  display: inline-block;
  color: var(--primary);
  background: #edf6ff;
  border-radius: 4px;
  padding: 3px 8px;
  font-size: 12px;
}
.kb-document h3 {
  font-size: 16px;
  line-height: 1.6;
  margin: 12px 0 8px;
  overflow-wrap: anywhere;
}
.kb-document > p {
  color: #74859a;
  font-size: 12px;
}
.kb-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 12px;
  border-top: 1px solid #ebedf0;
  padding-top: 8px;
}
.kb-actions button,
.kb-source button,
.kb-history button,
.kb-close {
  border: 0;
  background: transparent;
  color: var(--primary);
  padding: 8px 0;
  font: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}
.kb-delete,
.kb-history button:last-child {
  color: #ee0a24;
  flex-shrink: 0;
}
.kb-note {
  color: var(--primary);
  background: #edf6ff;
  border-radius: 8px;
  padding: 12px 16px;
  margin: 12px 0;
  font-size: 13px;
  line-height: 1.7;
}
.import-panel > summary {
  color: #323233;
  font-weight: 600;
  font-size: 16px;
  line-height: 1.6;
  cursor: pointer;
}
.kb-panel label {
  display: block;
  min-width: 0;
  font-size: 14px;
  margin: 14px 0;
}
.kb-panel input,
.kb-panel textarea,
.kb-panel select {
  display: block;
  box-sizing: border-box;
  width: 100%;
  min-width: 0;
  max-width: 100%;
  min-height: 44px;
  padding: 12px;
  margin-top: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: #f7f8fa;
  color: #323233;
  font: inherit;
  font-size: 14px;
  line-height: 1.5;
}
.kb-panel textarea {
  resize: vertical;
}
.kb-panel input::placeholder,
.kb-panel textarea::placeholder {
  color: #74859a;
}
.kb-panel input[type="file"] {
  font-size: 12px;
}
.kb-panel input::file-selector-button {
  border: 0;
  border-radius: 4px;
  padding: 6px 10px;
  margin-right: 8px;
  background: #edf6ff;
  color: var(--primary);
  cursor: pointer;
}
.kb-page :is(input, textarea, select, button, summary, a):focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}
.kb-two {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 12px;
}
.kb-source {
  padding: 16px 0 0;
  margin-top: 16px;
  border-top: 1px solid #ebedf0;
  overflow-wrap: anywhere;
}
.kb-source h3 {
  font-size: 15px;
  line-height: 1.6;
  margin: 10px 0;
}
.kb-source p {
  font-size: 14px;
  line-height: 1.8;
  white-space: pre-wrap;
}
.kb-source summary {
  color: #323233;
  font-size: 14px;
  line-height: 1.7;
  cursor: pointer;
}
.kb-source a,
.kb-document-view > a {
  color: var(--primary);
  font-size: 13px;
}
.kb-source button {
  margin-left: 16px;
}
.kb-ref {
  display: inline-block;
  color: var(--primary);
  background: #edf6ff;
  border-radius: 4px;
  padding: 2px 6px;
  font-size: 12px;
}
.kb-claim {
  padding: 12px 0;
  border-bottom: 1px solid #ebedf0;
}
.kb-claim > p {
  line-height: 1.8;
  font-size: 15px;
  overflow-wrap: anywhere;
}
.kb-claim summary {
  padding: 6px 0;
  font-size: 13px;
  color: var(--primary);
  cursor: pointer;
}
.kb-claim blockquote {
  margin: 8px 0;
  padding: 12px;
  border-left: 3px solid var(--primary);
  border-radius: 0 8px 8px 0;
  background: #f2f8ff;
  color: #646566;
  font-size: 14px;
  line-height: 1.8;
  overflow-wrap: anywhere;
}
.kb-abstain {
  padding: 12px;
  border-radius: 8px;
  background: #fffbe8;
  color: #9b7400 !important;
}
.kb-history {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border-top: 1px solid #ebedf0;
  padding: 8px 0;
}
.kb-history button:first-child {
  flex: 1;
  min-width: 0;
  color: #323233;
  font-size: 14px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.kb-history small {
  display: block;
  color: #74859a;
  font-size: 12px;
  margin-top: 4px;
}
.kb-document-view {
  padding: 20px 16px 32px;
  background: #fff;
  color: #323233;
}
.kb-document-view h2 {
  margin-top: 16px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.kb-document-view h3 {
  font-size: 16px;
  margin: 20px 0 12px;
}
.kb-document-view pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
  line-height: 1.8;
  font-size: 14px;
}
.kb-close {
  float: right;
}
.kb-progress {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
}
@media (max-width: 480px) {
  .kb-two {
    grid-template-columns: minmax(0, 1fr);
    gap: 0;
  }
}
.kb-page .kb-content { max-width: 920px; }
.kb-overview { border-top: 3px solid var(--primary); }
.kb-overview-title h1 { font-size: 22px; }
.kb-tabs { border: 1px solid var(--line); border-radius: 12px; box-shadow: none; }
.kb-document-view { padding: 28px; }
.kb-panel > .van-button--block { max-width: 360px; }
.kb-small { color: #74859a !important; }
@media (max-width: 599px) {
  .kb-overview-title h1 { font-size: 19px; }
  .kb-document-view { padding: 22px 20px; }
  .kb-panel > .van-button--block { max-width: none; }
}
.pack-actions {display:flex;align-items:center;gap:16px;flex-wrap:wrap;margin:14px 0;}
.pack-actions a {font-size:13px;}
.pack-actions .van-button {font-size:13px;}
.pack-preview {padding:16px;border:1px solid #dae7fb;background:#f6f9ff;border-radius:12px;margin-top:16px;}
.pack-preview > p {font-size:12px;line-height:1.8;}
.pack-preview details {font-size:12px;color:#647e9e;}
.pack-preview ul {max-height:180px;overflow:auto;padding-left:18px;line-height:1.9;}
.kb-overview > .van-button {margin-top:12px;}
</style>
