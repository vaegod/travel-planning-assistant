// Real local acceptance, using an isolated account. Never edits the original account.
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {localClient,testAccount} from './local-api.mjs';
import {readChatResponse} from '../travel-h5-java/src/utils/chatStream.ts';
if(!process.argv.includes('--live'))throw Error('Use --live for real provider calls');
const account=testAccount(),client=localClient('http://127.0.0.1:5173');
await writeFile('.runtime/pure-model-live-account.json',JSON.stringify(account)+'\n');
await client.register(account);
const record={startedAt:new Date().toISOString(),turns:[],planAttempts:[],checks:{}};
try{
  const doc=await client.api('/api/knowledge/documents','POST',{
    title:'纯模型隔离验收：虚构资料',city:'浙江省杭州市',sourceUrl:'',
    content:'这是一份仅供软件隔离验收的虚构资料。茶文化活动的测试编号为银杉4826，测试集合地点为紫藤书屋。本文不代表真实活动或真实规则，普通聊天不应自动读取本资料。',
  });
  record.extensionDocumentId=doc.document.id;
  const conv=await client.api('/api/conversations','POST',{title:'纯模型问答与上下文验收'});
  record.conversationId=conv.id;
  for(const [message,flag] of [
    ['西安最广为人知的古称是什么？20字内。',undefined],
    ['帮我规划杭州两日游，预算800元，100字内。',undefined],
    ['根据我上传的资料，茶文化活动的测试编号是什么？不知道请说明，30字内。',true],
    ['它的来源呢？请给出出处，40字内。',true],
    ['茶叶博物馆的预约规定是什么？不确定请说明，60字内。',false],
    ['我打算去成都，玩2天，预算800元。先记住这三个条件，简短回复。',undefined],
    ['把预算改为1200元，城市和天数不变。简短复述。',undefined],
    ['今天预约平台还剩多少张票？不知道请说明，40字内。',true],
  ]){
    const requestId=crypto.randomUUID(),start=performance.now();let answer='';const types=[];
    const body={conversationId:conv.id,requestId,message};if(flag!==undefined)body.useKnowledge=flag;
    const response=await client.request('/api/travel/chat','POST',body,185000);
    await readChatResponse(response,requestId,c=>answer+=c,undefined,()=>assert.fail('Pure chat must not emit evidence'));
    const row={requestId,question:message,legacyFlag:flag??null,answer,durationMs:Math.round(performance.now()-start)};
    record.turns.push(row);assert(answer.trim());assert(!answer.includes('银杉4826'));
    const page=await client.api(`/api/conversations/${conv.id}/messages`);
    const saved=page.items.find(m=>m.role==='assistant'&&m.requestId===requestId);
    assert.equal(saved.status,'completed');assert.equal(saved.content,answer);assert.equal(saved.evidence,null);
  }
  const state=await client.api(`/api/conversations/${conv.id}`);
  assert.equal(state.context.city,'成都');assert.equal(state.context.days,2);assert.equal(Number(state.context.budget),1200);
  record.context=state.context;
  const start=performance.now();
  const response=await client.request('/api/travel/recommend','POST',{city:'杭州',days:1,budget:1000},150000);
  const body=await response.json();
  record.planAttempts.push({httpStatus:response.status,errorCode:body.errorCode||null,durationMs:Math.round(performance.now()-start)});
  assert.equal(response.status,200);assert.equal(body.success,true);
  const plan=body.data;assert.equal(plan.dailyItinerary.length,1);assert.equal(plan.evidence,null);
  const saved=await client.api('/api/trips','POST',{title:'纯模型规划验收',plan});
  record.savedTripId=saved.trip.id;
  await client.login(account);
  assert.equal((await client.api(`/api/trips/${saved.trip.id}`)).plan.dailyItinerary.length,1);
  assert.equal((await client.api(`/api/conversations/${conv.id}/messages`)).items.length,16);
  assert.equal((await client.api(`/api/knowledge/documents/${doc.document.id}`)).content.includes('银杉4826'),true);
  const log=(await readFile('.runtime/backend.log','utf8')).split(/\r?\n/);
  const calls=log.flatMap(l=>{const m=l.match(/model_call (\{.*\})$/);return m?[JSON.parse(m[1])]:[];});
  for(const turn of record.turns){
    const models=calls.filter(c=>c.requestId===turn.requestId);
    const embeddings=log.filter(l=>l.includes('embedding_call requestId='+turn.requestId));
    assert.equal(models.length,1);assert.equal(models[0].promptVersion,'travel-chat-pure-v1');assert.equal(embeddings.length,0);
    turn.callAudit={generationCalls:models.length,embeddingCalls:0,promptVersion:models[0].promptVersion};
  }
  const planCalls=calls.filter(c=>c.promptVersion==='travel-direct-v1');assert.equal(planCalls.length,1);
  assert(!log.some(l=>l.includes('embedding_call requestId='+planCalls[0].requestId)));
  record.planAttempts[0].requestId=planCalls[0].requestId;
  record.planAttempts[0].callAudit={generationCalls:1,embeddingCalls:0};
  record.checks={pureChat:true,legacyFlagsIgnored:true,noEmbeddingQueries:true,noEvidenceEvents:true,
    contextPersistence:true,sourceIsolation:true,planningAndSave:true,reloginRestore:true,extensionDataRetained:true};
  // Export only this isolated session for an independent browser verification.
  const login=await client.request('/api/auth/login','POST',account);assert.equal(login.status,200);
  const rawCookie=login.headers.getSetCookie().find(v=>v.startsWith('JSESSIONID='));assert(rawCookie);
  const value=rawCookie.split(';')[0].slice('JSESSIONID='.length);
  await writeFile('.runtime/pure-model-browser-state.json',JSON.stringify({cookies:[{name:'JSESSIONID',value,domain:'127.0.0.1',path:'/',expires:-1,httpOnly:true,secure:false,sameSite:'Lax'}],origins:[]})+'\n');
  record.passed=true;
}catch(e){record.passed=false;record.failure={name:e.name,message:e.message};throw e;
}finally{
  record.completedAt=new Date().toISOString();await writeFile('docs/纯模型问答验收.json',JSON.stringify(record,null,2)+'\n');
}
console.log(JSON.stringify({passed:record.passed,turns:record.turns.length,checks:record.checks}));
