import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync,existsSync} from 'node:fs';
import {destinations} from '../src/utils/cityCatalog.ts';
import {decodeKnowledgePack} from '../src/utils/knowledgePack.ts';
import {validChatEvidence,chatEvidenceLabel} from '../src/utils/chatEvidence.js';
import {readChatResponse,createChatRequest} from '../src/utils/chatStream.ts';
import {mergeMessages} from '../src/utils/messageState.ts';
const id='00000000-0000-4000-8000-000000000001';
const evidence={mode:'KNOWLEDGE',city:'陕西省西安市',notice:'依据知识库',sources:[{ref:'C1',chunkId:'chunk',documentId:'doc',title:'旅行测试',city:'陕西省西安市',sourceUrl:'https://example.org/guide',content:'这是一份专门用来测试引用原文的公开旅行资料。'}],claims:[{text:'资料支持的结论',citations:[{ref:'C1',quote:'专门用来测试引用原文的公开旅行资料'}]}]};
const frame=(type,extra={})=>`data: ${JSON.stringify({type,requestId:id,...extra})}\n\n`;
const response=text=>new Response(text,{headers:{'content-type':'text/event-stream'}});
const doc={title:'目的地旅行资料',city:'陕西省西安市',sourceUrl:'https://example.org/guide',content:'这是一份用于验证全国城市知识库导入与问答的测试资料，资料内容是虚构的。'};
const bytes=value=>new TextEncoder().encode(JSON.stringify(value));
const pack={format:'travel-knowledge/v1',title:'样例',license:'CC BY 4.0',documents:[doc]};

test('home and backend destination catalogs stay identical',()=>{
  assert.deepEqual(JSON.parse(readFileSync('../travel-java-server/src/main/resources/knowledge/destinations.json','utf8')),destinations);
});
test('no public corpus is shipped and the extension can still parse user supplied packs',()=>{
  assert.equal(existsSync(new URL('../public/knowledge-packs/',import.meta.url)),false);
  assert.equal(decodeKnowledgePack('own-pack.json',bytes(pack)).documents[0].content,doc.content);
});
test('batch parser keeps city and source metadata and rejects unsupported formats',()=>{
  assert.equal(decodeKnowledgePack('source.json',bytes(pack)).documents[0].city,doc.city);
  for(const value of [{documents:[doc]},{...pack,documents:[]},{...pack,documents:Array(1001).fill(doc)}]) assert.throws(()=>decodeKnowledgePack('source.json',bytes(value)));
  assert.throws(()=>decodeKnowledgePack('source.csv',bytes(pack)));
  assert.throws(()=>decodeKnowledgePack('source.json',new Uint8Array([0xff])));
});
test('invalid documents are rejected before any batch import can start',()=>{
  for(const changes of [{city:''},{city:'西安\n杭州'},{content:'太短'},{sourceUrl:'javascript:alert(1)'},{sourceUrl:'https://name:pass@example.org/x'},{sourceUrl:'https://example.org/x#section'},{publishedOn:'2026-02-30'},{validFrom:'2026-10-18',validTo:'2026-10-17'}])
    assert.throws(()=>decodeKnowledgePack('source.json',bytes({...pack,documents:[doc,{...doc,...changes}]})));
});
test('stream metadata arrives once before content and survives chronological message restoration',async()=>{
  const seen=[],chunks=[];
  await readChatResponse(response(frame('start')+frame('evidence',{evidence})+frame('chunk',{content:'回答'})+frame('done')),id,c=>chunks.push(c),undefined,v=>seen.push(v));
  assert.deepEqual(seen,[evidence]);assert.deepEqual(chunks,['回答']);
  const restored=mergeMessages([], [{id:'a',seq:1,requestId:id,role:'assistant',content:'回答',status:'completed',createdAt:'2026-10-18T00:00:00Z',evidence}]);
  assert.deepEqual(restored[0].evidence,evidence);
});
test('invalid, duplicate, late or out-of-order evidence cannot become trusted citations',async()=>{
  const bad=structuredClone(evidence);bad.claims[0].citations[0].quote='这段原文完全不存在于资料内';
  assert.equal(validChatEvidence(bad),false);
  assert.equal(validChatEvidence({...evidence,mode:'MODEL_NO_MATCH'}),false);
  for(const text of [frame('evidence',{evidence}),frame('start')+frame('evidence',{evidence:bad}),frame('start')+frame('evidence',{evidence})+frame('evidence',{evidence}),frame('start')+frame('chunk',{content:'先回答'})+frame('evidence',{evidence})])
    await assert.rejects(readChatResponse(response(text),id,()=>{}),{code:'STREAM_PROTOCOL_ERROR'});
});
test('normal chat sends no knowledge toggle and accepts a pure model stream',async()=>{
  let sent;const chunks=[];
  const request=createChatRequest({baseUrl:'/api/travel',conversationId:'c',message:'聊旅行',city:'陕西省西安市',onChunk:c=>chunks.push(c),requestId:id,fetchImpl:async(url,options)=>{sent=JSON.parse(options.body);return response(frame('start')+frame('chunk',{content:'旅行建议'})+frame('done'));}});
  await request.finished;
  assert.equal(sent.city,'陕西省西安市');assert.equal('useKnowledge' in sent,false);assert.deepEqual(chunks,['旅行建议']);
});

test('unconfirmed requested sources remain explicit through streaming and restored history',async()=>{
  const unavailable={mode:'KNOWLEDGE_UNAVAILABLE',city:'',notice:'未找到资料，未根据模型常识补写答案。',sources:[],claims:[]};
  assert.equal(validChatEvidence(unavailable),true);
  assert.equal(validChatEvidence({...unavailable,sources:evidence.sources}),false);
  assert.equal(chatEvidenceLabel(unavailable.mode),'资料未能确认');
  const chunks=[];let received;
  await readChatResponse(response(frame('start')+frame('evidence',{evidence:unavailable})+frame('chunk',{content:unavailable.notice})+frame('done')),id,c=>chunks.push(c),undefined,e=>received=e);
  assert.deepEqual(received,unavailable);assert.equal(chunks.join(''),unavailable.notice);
  const restored=mergeMessages([], [{id:'u',seq:1,requestId:id,role:'assistant',content:unavailable.notice,status:'completed',createdAt:'2026-10-18T00:00:00Z',evidence:unavailable}]);
  assert.deepEqual(restored[0].evidence,unavailable);
});

test('historical fallback evidence still renders as model output',()=>{
  for(const mode of ['MODEL_ONLY','MODEL_NO_MATCH','MODEL_INSUFFICIENT','MODEL_RETRIEVAL_ERROR']) {
    assert.equal(validChatEvidence({mode,city:'',notice:'历史记录',sources:[],claims:[]}),true);
    assert.equal(chatEvidenceLabel(mode),'模型生成');
  }
  assert.equal(chatEvidenceLabel('KNOWLEDGE'),'依据知识库');
});
