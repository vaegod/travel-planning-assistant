// Read-only account snapshots. Full business records stay in ignored .runtime.
import assert from 'node:assert/strict';
import {readFile,writeFile} from 'node:fs/promises';
import {createHash} from 'node:crypto';
import {localClient} from './local-api.mjs';
const beforeMode=process.argv.includes('--before');
assert(beforeMode||process.argv.includes('--after'),'Use --before or --after');
const client=localClient('http://127.0.0.1:5173');
await client.login(JSON.parse((await readFile('.runtime/demo-account.json','utf8')).replace(/^\uFEFF/,'')));
async function pages(path){const items=[];for(let page=1;;page++){const r=await client.api(`${path}?page=${page}&pageSize=50`);items.push(...r.items);if(items.length>=r.total)return items.sort((a,b)=>a.id.localeCompare(b.id));}}
const me=await client.api('/api/auth/me');
const [documents,trips,conversations]=await Promise.all([client.api('/api/knowledge/documents'),pages('/api/trips'),pages('/api/conversations')]);
const messages={};
for(const c of conversations){
  const items=[];let before;
  do{const r=await client.api(`/api/conversations/${c.id}/messages?pageSize=50${before?'&before='+before:''}`);
    items.push(...r.items);if(!r.hasMore)break;before=r.nextBefore;
  }while(before);
  messages[c.id]=items.sort((a,b)=>a.seq-b.seq);
}
const tripDetails={};
for(const t of trips){
  const detail=await client.api(`/api/trips/${t.id}`),versions={};
  for(let version=1;version<=t.currentVersion;version++)versions[version]=await client.api(`/api/trips/${t.id}?version=${version}`);
  tripDetails[t.id]={detail,versions};
}
const snapshot={identity:{id:me.id,username:me.username},documents:documents.sort((a,b)=>a.id.localeCompare(b.id)),trips,tripDetails,conversations,messages};
const hash=value=>createHash('sha256').update(JSON.stringify(value)).digest('hex');
const record={at:new Date().toISOString(),sha256:hash(snapshot),snapshot};
const path='.runtime/pure-model-account-before.json';
if(beforeMode){
  // Never silently replace a baseline when resuming work.
  let old;try{old=JSON.parse(await readFile(path,'utf8'));}catch(e){if(e.code!=='ENOENT')throw e;}
  if(old)assert.deepEqual(snapshot,old.snapshot);else await writeFile(path,JSON.stringify(record)+'\n');
}else{
  const original=JSON.parse(await readFile(path,'utf8'));assert.deepEqual(snapshot,original.snapshot);
  await writeFile('.runtime/pure-model-account-after.json',JSON.stringify(record)+'\n');
  await writeFile('docs/纯模型账号数据保留验收.json',JSON.stringify({passed:true,username:me.username,userId:me.id,
    beforeAt:original.at,afterAt:record.at,beforeSha256:original.sha256,afterSha256:record.sha256,
    documents:documents.length,chunks:documents.reduce((n,d)=>n+d.chunkCount,0),trips:trips.length,
    conversations:conversations.length,messages:Object.values(messages).reduce((n,v)=>n+v.length,0),
    scope:'identity, document metadata, trip metadata/details/version lists, all conversation metadata and all messages including historical evidence'},null,2)+'\n');
}
console.log(JSON.stringify({passed:true,phase:beforeMode?'before':'after',documents:documents.length,trips:trips.length,conversations:conversations.length}));
