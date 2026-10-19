// Live local-app acceptance. Credentials stay in ignored .runtime; no user account is edited.
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {localClient,testAccount} from './local-api.mjs';
import {readChatResponse} from '../travel-h5-java/src/utils/chatStream.ts';
const root=process.env.VERIFY_ROOT||'http://localhost:5173';
const suffix=new URL(root).port||'default';
const account=testAccount(),client=localClient(root),record={at:new Date().toISOString(),origin:root,kind:'real-model-and-embedding',checks:{}};
await writeFile(`.runtime/integrated-knowledge-${suffix}-account.json`,JSON.stringify(account)+'\n');
const manifest=await (await fetch(root+'/knowledge-packs/manifest.json')).json();
const download=Buffer.from(await (await fetch(root+manifest.packs[0].url)).arrayBuffer());
assert.equal(createHash('sha256').update(download).digest('hex'),manifest.packs[0].sha256);
const pack=JSON.parse(download);
record.publicPack={documents:pack.documents.length,catalog:manifest.catalogDestinations,covered:manifest.coveredDestinations,sha256:manifest.packs[0].sha256};
await client.register(account);
for(const city of ['陕西省西安市','四川省成都市']){
  const doc=pack.documents.find(d=>d.city===city);assert(doc);
  await client.api('/api/knowledge/documents','POST',doc);
  const duplicate=await client.api('/api/knowledge/documents','POST',doc);
  assert.equal(duplicate.duplicate,true);
}
record.checks.publicPackAndDuplicateImport=true;
for(const city of ['西安','西安市','陕西省西安市']){
  const found=await client.api('/api/knowledge/search','POST',{question:'西安古称什么？',city,topK:4});
  assert(found.matches.length>0);
  assert(found.matches.every(s=>s.city==='陕西省西安市'));
}
record.checks.cityAliases=true;
const all=await client.api('/api/knowledge/search','POST',{question:'成都的别称是什么？',city:'',topK:4});
assert(all.matches.length>0);record.checks.allCitySearch=true;
const conv=await client.api('/api/conversations','POST',{});
record.conversationId=conv.id;record.turns=[];
async function chat(message,city,expected){
  const requestId=crypto.randomUUID(),start=performance.now();let answer='',evidence;
  const response=await client.request('/api/travel/chat','POST',{conversationId:conv.id,requestId,message,city},185000);
  await readChatResponse(response,requestId,c=>answer+=c,undefined,v=>evidence=v);
  assert(answer.trim());assert.equal(evidence?.mode,expected,JSON.stringify(evidence));
  if(expected==='KNOWLEDGE'){
    assert(evidence.sources.length>0);
    for(const claim of evidence.claims)for(const citation of claim.citations)assert(evidence.sources.some(s=>s.ref===citation.ref&&s.content.includes(citation.quote)));
  }else assert.equal(evidence.sources.length,0);
  record.turns.push({question:message,answer,evidence,durationMs:Math.round(performance.now()-start)});
  console.log('Live turn passed: '+expected);
}
try{
  await chat('西安古称什么？请根据资料简要回答。','陕西省西安市','KNOWLEDGE');
  await chat('它有哪些历史人文景点？',undefined,'KNOWLEDGE');
  await chat('推荐泉州适合一天游览的景点，100字以内。','福建省泉州市','MODEL_ONLY');
  const saved=await client.api(`/api/conversations/${conv.id}/messages`);
  const replies=saved.items.filter(m=>m.role==='assistant');assert.equal(replies.length,3);
  assert(replies.every(m=>m.status==='completed'));
  for(let i=0;i<replies.length;i++)assert.deepEqual(replies[i].evidence,record.turns[i].evidence);
  record.checks.persistedSourcesAndFallback=true;
  if(process.env.VERIFY_DIRECT_PLAN==='true'){
    const plan=await client.api('/api/travel/recommend','POST',{city:'陕西省西安市',days:1,budget:1000});
    assert.equal(plan.city,'陕西省西安市');assert.equal(plan.dailyItinerary.length,1);assert.equal(plan.evidence,null);
    record.checks.directPlanning=true;
  }
  record.passed=true;
}finally{
  await writeFile(`.runtime/integrated-knowledge-${suffix}-live.json`,JSON.stringify(record,null,2)+'\n');
}
console.log(JSON.stringify({passed:record.passed,checks:record.checks,publicPack:record.publicPack}));
