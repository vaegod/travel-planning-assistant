// Live, opt-in acceptance against the local app. Credentials remain in ignored .runtime.
import assert from 'node:assert/strict';
import {readFile,writeFile,mkdir,copyFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {localClient,testAccount} from './local-api.mjs';
import {readChatResponse} from '../travel-h5-java/src/utils/chatStream.ts';
if(!process.argv.includes('--live'))throw Error('Use --live to call the configured model and embedding services');
const origin=process.env.VERIFY_ROOT||'http://127.0.0.1:5173';
const digest=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
const resume=process.argv.includes('--complete-plan');
const record=resume?JSON.parse(await readFile('docs/按需RAG验收结果.json','utf8'))
  :{startedAt:new Date().toISOString(),origin,kind:'real-model-and-embedding',turns:[],checks:{},directPlanAttempts:[]};
const original=localClient(origin);
const credentials=JSON.parse((await readFile('.runtime/demo-account.json','utf8')).replace(/^\uFEFF/,''));
await original.login(credentials);
async function pages(path){
  const items=[];
  for(let page=1;;page++){
    const result=await original.api(`${path}?page=${page}&pageSize=50`);
    items.push(...result.items);
    if(items.length>=result.total)return items.sort((a,b)=>a.id.localeCompare(b.id));
  }
}
async function snapshot(){
  const [documents,trips,conversations]=await Promise.all([original.api('/api/knowledge/documents'),pages('/api/trips'),pages('/api/conversations')]);
  return {documents:documents.sort((a,b)=>a.id.localeCompare(b.id)),trips,conversations};
}
const before=await snapshot();
const account=resume?JSON.parse(await readFile('.runtime/on-demand-acceptance-account.json','utf8')):testAccount(),client=localClient(origin);
await mkdir('.runtime',{recursive:true});
await writeFile('.runtime/on-demand-acceptance-account.json',JSON.stringify(account)+'\n');
if(resume)await client.login(account);else await client.register(account);
const newConversation=title=>client.api('/api/conversations','POST',{title:'按需检索验收 '+title});
async function chat(conversation,message,expected,{enabled=true,contains}={}){
  const requestId=crypto.randomUUID(),start=performance.now();let answer='',evidence;
  const response=await client.request('/api/travel/chat','POST',{
    conversationId:conversation.id,requestId,message,city:'浙江省杭州市',useKnowledge:enabled,
  },185000);
  await readChatResponse(response,requestId,c=>answer+=c,undefined,e=>evidence=e);
  const row={requestId,conversationId:conversation.id,question:message,expected,enabled,answer,evidence,durationMs:Math.round(performance.now()-start)};
  record.turns.push(row);
  assert(answer.trim()); assert.equal(evidence?.mode,expected);
  if(contains)assert(answer.includes(contains),`Expected answer to include ${contains}`);
  if(expected==='KNOWLEDGE'){
    for(const claim of evidence.claims)for(const cite of claim.citations){
      const source=evidence.sources.find(s=>s.ref===cite.ref);assert(source?.content.includes(cite.quote));
      const detail=await client.api(`/api/knowledge/documents/${source.documentId}`);
      assert(detail.chunks.some(c=>c.id===source.chunkId&&c.content===source.content));
    }
  }else assert.equal(evidence.sources.length,0);
  const saved=await client.api(`/api/conversations/${conversation.id}/messages`);
  const reply=saved.items.find(m=>m.requestId===requestId&&m.role==='assistant');
  assert.equal(reply?.status,'completed');assert.equal(reply.content,answer);assert.deepEqual(reply.evidence,evidence);
  console.log(JSON.stringify({event:'turn',mode:expected,durationMs:row.durationMs}));
}
try{
  if(!resume){
  await chat(await newConversation('空资料'), '我上传的茶文化活动规则中，取消条件是什么？','KNOWLEDGE_UNAVAILABLE');
  const imported=await client.api('/api/knowledge/documents','POST',{
    title:'按需检索功能验收：虚构茶文化活动规则',city:'浙江省杭州市',sourceUrl:'',
    content:'这是一份专门用于软件功能验收的虚构茶文化活动资料，不代表真实活动或真实退改规则。茶文化活动在开始前48小时及以上取消可全额退款。活动测试编号为蓝鲸9017，测试集合地点为松林书角。请只将本文作为功能测试资料。',
  });
  record.testDocumentId=imported.document.id;
  await chat(await newConversation('普通问答'),'西安最广为人知的古称是什么？30字内。','MODEL_ONLY');
  await chat(await newConversation('聊天行程'),'帮我规划杭州两日游，预算800元，120字内。','MODEL_ONLY');
  await chat(await newConversation('规则'),'茶文化活动取消规则是什么？','KNOWLEDGE', {contains:'48小时'});
  const followup=await newConversation('私人资料与追问');
  await chat(followup,'根据我上传的资料，茶文化活动测试编号和集合地点是什么？','KNOWLEDGE',{contains:'蓝鲸9017'});
  await chat(followup,'它的集合地点呢？','KNOWLEDGE',{contains:'松林书角'});
  await chat(followup,'换个话题，介绍杭州的美食，80字内。','MODEL_ONLY');
  await chat(followup,'它适合老人吗？80字内。','MODEL_ONLY');
  const sources=await newConversation('要求来源');
  await chat(sources,'茶文化活动测试编号是什么？20字内。','MODEL_ONLY');
  await chat(sources,'给出来源','KNOWLEDGE',{contains:'蓝鲸9017'});
  await chat(await newConversation('资料不足'),'根据我上传的茶文化活动资料，迟到30分钟的赔偿条款是什么？','KNOWLEDGE_UNAVAILABLE');
  await chat(await newConversation('关闭检索'),'我上传的茶文化活动资料中测试编号是什么？','KNOWLEDGE_UNAVAILABLE',{enabled:false});
  await chat(await newConversation('实时来源'),'请给出来源，今天茶叶博物馆还剩多少张预约余票？','KNOWLEDGE_UNAVAILABLE');
  }
  const start=performance.now();
  const city=process.env.VERIFY_PLAN_CITY||(resume?'杭州':'浙江省杭州市');
  const planResponse=await client.request('/api/travel/recommend','POST',{city,days:1,budget:1000},150000);
  const planBody=await planResponse.json();
  record.directPlanAttempts.push({city,httpStatus:planResponse.status,errorCode:planBody.errorCode||null,
    durationMs:Math.round(performance.now()-start)});
  assert.equal(planResponse.status,200);
  assert.equal(planBody.success,true);
  const plan=planBody.data;
  assert.equal(plan.dailyItinerary.length,1);assert.equal(plan.evidence,null);
  record.directPlan={days:plan.dailyItinerary.length,evidence:plan.evidence,durationMs:Math.round(performance.now()-start)};
  assert.deepEqual(await snapshot(),before);
  record.originalAccount={username:credentials.username,documents:before.documents.length,
    chunks:before.documents.reduce((n,d)=>n+d.chunkCount,0),trips:before.trips.length,conversations:before.conversations.length,
    beforeSha256:digest(before),afterSha256:digest(await snapshot())};
  assert.equal(record.originalAccount.beforeSha256,record.originalAccount.afterSha256);
  record.checks={ordinaryBypass:true,rules:true,uploadedNotes:true,contextFollowup:true,topicReset:true,
    explicitSources:true,missingEvidenceAbstains:true,disabledRetrieval:true,staticRealtimeBoundary:true,
    persistedEvidence:true,directPlanning:true,originalAccountUnchanged:true};
  record.passed=true;
  delete record.failure;
}catch(error){
  record.passed=false;record.failure={name:error.name,message:error.message};throw error;
}finally{
  record.completedAt=new Date().toISOString();
  try{await copyFile('.runtime/backend.log','.runtime/on-demand-live-provider.log');
    record.providerLog='.runtime/on-demand-live-provider.log';}catch{/* Remote local deployments may not expose their log. */}
  await writeFile('docs/按需RAG验收结果.json',JSON.stringify(record,null,2)+'\n');
}
console.log(JSON.stringify({passed:record.passed,turns:record.turns.length,checks:record.checks}));
