// Opt-in live comparison of the application's actual RAG toggle. Never prints credentials.
import assert from 'node:assert/strict';
import {readFile,writeFile,rename,mkdir} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {localClient} from './local-api.mjs';
import {readChatResponse} from '../travel-h5-java/src/utils/chatStream.ts';
if(!process.argv.includes('--live'))throw Error('Real provider calls require the explicit --live argument');
const hash=data=>createHash('sha256').update(data).digest('hex');
const json=async path=>JSON.parse((await readFile(path,'utf8')).replace(/^\uFEFF/,''));
const origin=process.env.EVAL_ROOT||'http://127.0.0.1:5173';
const client=localClient(origin),account=await json(process.env.EVAL_ACCOUNT_FILE||'.runtime/demo-account.json');
assert.equal(account.username,'wl');await client.login(account);
const me=await client.api('/api/auth/me');assert.equal(me.username,'wl');
const datasetBytes=await readFile('eval/national-rag-ab-cases.json'),dataset=JSON.parse(datasetBytes);
assert.equal(dataset.cases.length,20);assert.equal(dataset.repetitions,2);
const docs=await client.api('/api/knowledge/documents');
const corpusHash=value=>hash(JSON.stringify([...value].sort((a,b)=>a.id.localeCompare(b.id))));
const corpusFingerprint=corpusHash(docs);
assert.equal(docs.length,545,'Use the unchanged, already imported wl corpus');
const manifest=await json('travel-h5-java/public/knowledge-packs/manifest.json');
const checkpoint=process.env.EVAL_CHECKPOINT||'.runtime/national-rag-ab-run.json';
const codeFiles=['service/TravelService.java','service/ChatContext.java','knowledge/ChatKnowledge.java','knowledge/RagEngine.java','knowledge/KnowledgeService.java','utils/LLMutils.java'];
const codeHashes={};for(const file of codeFiles)codeHashes[file]=hash(await readFile('travel-java-server/src/main/java/org/example/traveljavaserver/'+file));
let state;
try{state=await json(checkpoint);}catch{}
if(state){
  assert.equal(state.datasetSha256,hash(datasetBytes));assert.equal(state.corpus.metadataSha256,corpusFingerprint);
  assert.equal(state.userId,me.id);assert.deepEqual(state.codeSha256,codeHashes);
}else{
  let seed=dataset.seed>>>0;
  const random=()=>{seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed/4294967296;};
  const plan=[];
  for(let rep=0;rep<dataset.repetitions;rep++){
    const cases=dataset.cases.map((c,index)=>({...c,index}));
    for(let i=cases.length-1;i>0;i--){const j=Math.floor(random()*(i+1));[cases[i],cases[j]]=[cases[j],cases[i]];}
    for(const c of cases){
      const order=(c.index+rep)%2===0?[false,true]:[true,false];
      for(const enabled of order)plan.push({key:`${c.id}-${rep+1}-${enabled?'on':'off'}`,caseId:c.id,repetition:rep+1,ragEnabled:enabled});
    }
  }
  const [trips,conversations]=await Promise.all([client.api('/api/trips'),client.api('/api/conversations')]);
  state={startedAt:new Date().toISOString(),origin,username:'wl',userId:me.id,datasetVersion:dataset.version,
    datasetSha256:hash(datasetBytes),seed:dataset.seed,model:'Qwen/Qwen3.5-35B-A3B',enableThinking:false,
    settings:{rag:{temperature:0.2,maxOutputTokens:2048,json:true,topK:4,minScore:0.55},offAndFallback:{temperature:0.7,maxOutputTokens:4096,stream:true}},
    codeSha256:codeHashes,corpus:{documents:docs.length,chunks:docs.reduce((n,d)=>n+d.chunkCount,0),metadataSha256:corpusFingerprint,
      embeddingModels:[...new Set(docs.map(d=>d.embeddingModel))],dimensions:[...new Set(docs.map(d=>d.dimensions))],packSha256:manifest.packs[0].sha256},
    before:{trips,conversations},plan,warmups:[],results:[],dataset};
}
await mkdir('.runtime',{recursive:true});
async function save(){await writeFile(checkpoint+'.tmp',JSON.stringify(state,null,2)+'\n');await rename(checkpoint+'.tmp',checkpoint);}
async function requestAnswer(c,enabled,rep,key){
  const record={key,caseId:c.id,category:c.category,repetition:rep,ragEnabled:enabled,question:c.question,
    submittedQuestion:c.question+'\n'+dataset.instructionSuffix,city:c.city,startedAt:new Date().toISOString()};
  const conv=await client.api('/api/conversations','POST',{title:`RAG开关评测 ${c.id} 第${rep}轮 ${enabled?'开启':'关闭'}`});
  record.conversationId=conv.id;record.requestId=crypto.randomUUID();
  let answer='',evidence,firstContentMs=null,chunks=0;
  const start=performance.now();
  try{
    const response=await client.request('/api/travel/chat','POST',{conversationId:conv.id,requestId:record.requestId,message:record.submittedQuestion,city:c.city,useKnowledge:enabled},185000);
    await readChatResponse(response,record.requestId,chunk=>{firstContentMs??=Math.round(performance.now()-start);chunks++;answer+=chunk;},undefined,value=>{evidence=value;record.evidenceMs=Math.round(performance.now()-start);});
    assert(answer.trim());assert(evidence);
    if(!enabled)assert.equal(evidence.mode,'MODEL_ONLY');
    record.status='completed';
  }catch(error){record.status='error';record.error={code:error.code||error.name||'ERROR',message:error.message};}
  record.totalMs=Math.round(performance.now()-start);record.firstContentMs=firstContentMs;record.chunks=chunks;
  record.answer=answer;record.answerCharacters=answer.replace(/\s/g,'').length;record.evidence=evidence||null;
  const cites=evidence?.claims.flatMap(claim=>claim.citations)||[];
  record.citationIntegrity={citations:cites.length,exactQuotesValid:cites.filter(cite=>cite.quote.length>=8&&cite.quote.length<=180&&evidence.sources.some(source=>source.ref===cite.ref&&source.content.includes(cite.quote))).length};
  return record;
}
await save();
for(const enabled of [false,true]){
  if(state.warmups.some(r=>r.ragEnabled===enabled))continue;
  const result=await requestAnswer({id:'WARMUP',category:'warmup',city:'陕西省西安市',question:'西安是哪个省的省会？'},enabled,0,`warmup-${enabled}`);
  state.warmups.push(result);await save();
  console.log(JSON.stringify({event:'warmup',enabled,status:result.status,mode:result.evidence?.mode}));
}
for(const task of state.plan){
  if(state.results.some(r=>r.key===task.key))continue;
  const c=dataset.cases.find(c=>c.id===task.caseId);
  const result=await requestAnswer(c,task.ragEnabled,task.repetition,task.key);
  state.results.push(result);state.updatedAt=new Date().toISOString();await save();
  console.log(JSON.stringify({event:'result',completed:state.results.length,total:state.plan.length,caseId:c.id,repetition:task.repetition,
    enabled:task.ragEnabled,status:result.status,mode:result.evidence?.mode,totalMs:result.totalMs,firstContentMs:result.firstContentMs}));
}
const sourceDetails={};
for(const row of state.results){
  for(const source of row.evidence?.sources||[]){
    sourceDetails[source.documentId]??=await client.api(`/api/knowledge/documents/${source.documentId}`);
    const detail=sourceDetails[source.documentId],piece=detail.chunks.find(p=>p.id===source.chunkId);
    assert(piece&&piece.content===source.content&&piece.ordinal===source.ordinal,'Citation must resolve to the actual stored chunk');
    assert.equal(detail.document.sourceUrl,source.sourceUrl);
  }
  row.citationIntegrity.storedSourcesVerified=true;
}
const [afterDocs,afterTrips,afterConversations]=await Promise.all([client.api('/api/knowledge/documents'),client.api('/api/trips'),client.api('/api/conversations')]);
assert.equal(corpusHash(afterDocs),state.corpus.metadataSha256,'Do not score a moving corpus');assert.deepEqual(afterTrips,state.before.trips);
for(const old of state.before.conversations.items)assert.deepEqual(await client.api(`/api/conversations/${old.id}`),old);
state.after={documents:afterDocs.length,chunks:afterDocs.reduce((n,d)=>n+d.chunkCount,0),trips:afterTrips.total,conversations:afterConversations.total};
state.completedAt=new Date().toISOString();state.sourceSnapshots=Object.fromEntries(Object.entries(sourceDetails).map(([id,d])=>[id,{document:d.document,chunks:d.chunks}]));
state.completed=true;await save();
const exported={...state};delete exported.before;
await writeFile(process.env.EVAL_EXPORT||'docs/全国RAG开关对比原始结果.json',JSON.stringify(exported,null,2)+'\n');
console.log(JSON.stringify({event:'complete',results:state.results.length,failures:state.results.filter(r=>r.status==='error').length,corpusUnchanged:true,after:state.after}));
