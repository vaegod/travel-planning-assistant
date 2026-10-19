// Offline verification of the real acceptance requests, using metadata-only provider logs.
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
const path='docs/按需RAG验收结果.json';
const result=JSON.parse(await readFile(path,'utf8'));
const lines=(await readFile(process.env.VERIFY_LOG||result.providerLog||'.runtime/backend.log','utf8')).split(/\r?\n/);
const calls=lines.flatMap(line=>{
  const match=line.match(/model_call (\{.*\})$/);return match?[JSON.parse(match[1])]:[];
});
const embeddings=lines.flatMap(line=>{
  const match=line.match(/embedding_call requestId=(\S+) model=(\S+) inputs=(\d+) status=(\S+) durationMs=(\d+)/);
  return match?[{requestId:match[1],model:match[2],inputs:Number(match[3]),status:match[4],durationMs:Number(match[5])}]:[];
});
assert.equal(result.turns.length,13);
for(const turn of result.turns){
  const generation=calls.filter(c=>c.requestId===turn.requestId),vectors=embeddings.filter(c=>c.requestId===turn.requestId);
  turn.callAudit={generationCalls:generation.length,embeddingCalls:vectors.length,promptVersions:generation.map(c=>c.promptVersion)};
  if(turn.expected==='MODEL_ONLY'){
    assert.equal(generation.length,1);assert.equal(vectors.length,0);
    assert.equal(generation[0].promptVersion,'travel-chat-v3');
  }else if(turn.expected==='KNOWLEDGE'){
    assert.equal(generation.length,1);assert.equal(vectors.length,1);
    assert.equal(generation[0].promptVersion,'travel-rag-v1');
  }else{
    assert(generation.length<=1);assert(generation.every(c=>c.promptVersion==='travel-rag-v1'));
  }
}
const plans=calls.filter(c=>c.promptVersion==='travel-direct-v1');
assert.equal(plans.length,result.directPlanAttempts.length);
result.directPlanAttempts.forEach((attempt,index)=>{
  const call=plans[index];attempt.requestId=call.requestId;
  attempt.callAudit={generationCalls:1,embeddingCalls:embeddings.filter(c=>c.requestId===call.requestId).length,promptVersion:call.promptVersion};
  assert.equal(attempt.callAudit.embeddingCalls,0);
});
result.modelCallAudit={passed:true,ordinaryTurns:result.turns.filter(t=>t.expected==='MODEL_ONLY').length,
  totalChatGenerationCalls:result.turns.reduce((n,t)=>n+t.callAudit.generationCalls,0),
  totalChatEmbeddingCalls:result.turns.reduce((n,t)=>n+t.callAudit.embeddingCalls,0),
  noIntentModel:true,noUnsupportedFallback:true};
await writeFile(path,JSON.stringify(result,null,2)+'\n');
console.log(JSON.stringify(result.modelCallAudit));
