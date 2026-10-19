// Supplemental live regression for chains of source requests, using the isolated acceptance account.
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {localClient} from './local-api.mjs';
import {readChatResponse} from '../travel-h5-java/src/utils/chatStream.ts';
if(!process.argv.includes('--live'))throw Error('Use --live for real model calls');
const client=localClient(process.env.VERIFY_ROOT||'http://127.0.0.1:5173');
await client.login(JSON.parse(await readFile('.runtime/on-demand-acceptance-account.json','utf8')));
const conversation=await client.api('/api/conversations','POST',{title:'按需检索验收 连续来源追问补验'});
const result={startedAt:new Date().toISOString(),turns:[]};
try{
  for(const [question,mode,contains] of [
    ['茶文化活动测试编号是什么？20字内。','MODEL_ONLY',null],
    ['给出来源','KNOWLEDGE','蓝鲸9017'],
    ['它的集合地点呢？','KNOWLEDGE','松林书角'],
    ['给我来源','KNOWLEDGE','松林书角'],
    ['换个话题，介绍成都美食，60字内。','MODEL_ONLY',null],
    ['它适合老人吗？60字内。','MODEL_ONLY',null],
  ]){
    const requestId=crypto.randomUUID();let answer='',evidence;
    const response=await client.request('/api/travel/chat','POST',{
      conversationId:conversation.id,requestId,message:question,useKnowledge:true,city:'浙江省杭州市',
    },185000);
    await readChatResponse(response,requestId,c=>answer+=c,undefined,e=>evidence=e);
    result.turns.push({requestId,question,answer,evidence});
    assert.equal(evidence.mode,mode);if(contains)assert(answer.includes(contains));
    const saved=await client.api(`/api/conversations/${conversation.id}/messages`);
    const reply=saved.items.find(m=>m.requestId===requestId&&m.role==='assistant');
    assert.deepEqual(reply.evidence,evidence);assert.equal(reply.content,answer);
  }
  const logs=(await readFile('.runtime/backend.log','utf8')).split(/\r?\n/);
  for(const turn of result.turns){
    const models=logs.filter(l=>l.includes('model_call {')&&l.includes(turn.requestId));
    const embeddings=logs.filter(l=>l.includes('embedding_call requestId='+turn.requestId));
    assert.equal(models.length,1);
    assert.equal(embeddings.length,turn.evidence.mode==='KNOWLEDGE'?1:0);
    turn.callAudit={generationCalls:models.length,embeddingCalls:embeddings.length};
  }
  result.passed=true;
}finally{
  result.completedAt=new Date().toISOString();
  await writeFile('docs/按需RAG连续追问验收.json',JSON.stringify(result,null,2)+'\n');
}
console.log(JSON.stringify({passed:result.passed,turns:result.turns.length}));
