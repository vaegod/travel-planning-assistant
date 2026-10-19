// Read-only post-run diagnosis; deliberately excluded from timed comparisons.
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {localClient} from './local-api.mjs';
import {findDestination} from '../travel-h5-java/src/utils/cityCatalog.ts';
const run=JSON.parse(await readFile('docs/全国RAG开关对比原始结果.json','utf8'));assert(run.completed);
const account=JSON.parse((await readFile('.runtime/demo-account.json','utf8')).replace(/^\uFEFF/,''));
const client=localClient(run.origin);await client.login(account);
const docs=await client.api('/api/knowledge/documents');
const normalize=value=>findDestination(value)?.fullName||value.trim();
const terms={F01:[['长安']],F02:[['蓉城'],['锦官城','锦城']],F03:[['钟鼓楼','钟楼'],['永定门']],F04:[['崇圣寺']],
  F05:[['双峰'],['龙井'],['龙井路88'],['翁家山268']],F06:[['2011']],F07:[['1997'],['2000']],F08:[['镇国'],['仁寿']],
  F09:[['蛇山']],F10:[['朱元璋'],['马氏','马皇后']],F11:[['鸣沙山']],F12:[['中山路']],F13:[['长江'],['嘉陵江']],F14:[['红山','玛布日']]};
const cache=new Map();
function present(content,term){return /^\d+$/.test(term)?new RegExp(`(?<!\\d)${term}(?!\\d)`).test(content):content.replace(/\s/g,'').includes(term);}
function matches(content,groups){return groups.map(group=>group.some(term=>present(content,term)));}
function windows(text,groups){const values=[];for(const term of groups.flat()){
  let offset=text.indexOf(term);if(offset>=0)values.push({term,excerpt:text.slice(Math.max(0,offset-80),Math.min(text.length,offset+term.length+110))});
}return values;}
const rows=[];
for(const c of run.dataset.cases.filter(c=>c.category==='fact')){
  const cityDocs=docs.filter(doc=>normalize(doc.city)===c.city);
  const texts=[];
  for(const doc of cityDocs){if(!cache.has(doc.id))cache.set(doc.id,await client.api(`/api/knowledge/documents/${doc.id}`));texts.push(cache.get(doc.id).content);}
  const full=texts.map(s=>s.split('\n---\n')[0]).join('\n');
  const found=await client.api('/api/knowledge/search','POST',{question:c.question+'\n'+run.dataset.instructionSuffix,city:c.city,topK:4});
  const selected=found.matches.map(m=>m.content).join('\n');
  rows.push({id:c.id,city:c.city,question:c.question,keywordGroups:terms[c.id],cityDocuments:cityDocs.length,
    fullDocumentKeywordPresence:matches(full,terms[c.id]),retrievedKeywordPresence:matches(selected,terms[c.id]),
    fullDocumentContext:windows(full,terms[c.id]),retrievedContext:windows(selected,terms[c.id]),
    retrieved:found.matches,modes:run.results.filter(r=>r.caseId===c.id&&r.ragEnabled).map(r=>r.evidence.mode)});
}
const report={at:new Date().toISOString(),method:'计时结束后单独调用相同城市/问题检索；关键词及其上下文只定位可能的语料缺口，不是关系正确性评分或严格Recall@K。',cases:rows};
await writeFile('docs/全国RAG检索诊断.json',JSON.stringify(report,null,2)+'\n');
console.log(JSON.stringify(rows.map(r=>({id:r.id,full:r.fullDocumentKeywordPresence,top:r.retrievedKeywordPresence,modes:r.modes}))));
