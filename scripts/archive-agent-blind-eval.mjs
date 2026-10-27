import assert from "node:assert/strict";
import {readFile,writeFile,mkdir,access} from "node:fs/promises";
import {resolve,dirname} from "node:path";
import {hash} from "./agent-eval/protocol.mjs";
import {BLIND_FOLDER} from "./agent-eval/blind-contract.mjs";
const run=process.argv.find(x=>x.startsWith("--run="))?.slice(6);assert(run&&/^[a-zA-Z0-9_-]+$/.test(run));
const root=process.cwd(),folder="eval/agent-v1/results/"+run,destination=resolve(root,"eval/agent-v1/baselines",run);
try{await access(resolve(destination,"manifest.json"));throw Error("Refusing to overwrite blind archive");}
catch(error){if(error.code!=="ENOENT")throw error;}
const report=JSON.parse(await readFile(resolve(root,folder,"results.json"),"utf8"));
assert.equal(report.metadata.phase,"blind");assert.equal(report.metadata.experimentCompleted,true);assert.equal(report.results.length,40);
const sources={};
for(const[file,digest]of Object.entries(report.metadata.sourceFileSha256)){
  const bytes=await readFile(resolve(root,file));assert.equal(hash(bytes),digest,file);sources[file]=bytes;
}
const assets={};
for(const file of ["results.json","summary.json","report.md","baseline-reference.md","baseline-reference.json"])
  assets[folder+"/"+file]=hash(await readFile(resolve(root,folder,file)));
for(const file of ["plan.json","dataset.json","frozen-sandbox.json","seal.json","exposure.json"])
  assets[BLIND_FOLDER+"/"+file]=hash(await readFile(resolve(root,BLIND_FOLDER,file)));
for(const[file,bytes]of Object.entries(sources)){
  const target=resolve(destination,"source",file);await mkdir(dirname(target),{recursive:true});await writeFile(target,bytes,{flag:"wx"});
}
await writeFile(resolve(destination,"manifest.json"),JSON.stringify({run,archivedAt:new Date().toISOString(),
  sourceFileSha256:report.metadata.sourceFileSha256,jarSha256:report.metadata.jarSha256,assetSha256:assets,
  note:"First-use blind run, v10 application unchanged. Original 40-case regression and its results are never overwritten."},null,2)+"\n",{flag:"wx"});
console.log(JSON.stringify({archived:run,sourceFiles:Object.keys(sources).length,assetHashes:Object.keys(assets).length}));
