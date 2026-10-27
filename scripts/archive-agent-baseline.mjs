import assert from "node:assert/strict";
import {readFile,writeFile,mkdir,access} from "node:fs/promises";
import {resolve,dirname} from "node:path";
import {fileURLToPath} from "node:url";
import {createHash} from "node:crypto";
const root=fileURLToPath(new URL("../",import.meta.url));
const digest=b=>createHash("sha256").update(b).digest("hex");
const run=process.argv.find(x=>x.startsWith("--run="))?.slice(6)||"full-live-v3";
assert(/^[a-zA-Z0-9_-]+$/.test(run));
const destination=resolve(root,"eval/agent-v1/baselines",run);
try{await access(resolve(destination,"manifest.json"));throw Error("Baseline already archived; refusing overwrite");}
catch(e){if(e.code!=="ENOENT")throw e;}
const runRoot=resolve(root,"eval/agent-v1/results",run);
const report=JSON.parse(await readFile(resolve(runRoot,"results.json"),"utf8"));
assert.equal(report.metadata.experimentCompleted,true);
const source={};
for(const [path,expected]of Object.entries(report.metadata.sourceFileSha256)){
  const bytes=await readFile(resolve(root,path));assert.equal(digest(bytes),expected,path+" differs from measured baseline");
  source[path]=bytes;
}
const assets={};
for(const path of ["results.json","summary.json","report.md"])
  assets["eval/agent-v1/results/"+run+"/"+path]=digest(await readFile(resolve(runRoot,path)));
for(const path of ["eval/agent-v1/dataset.json","eval/agent-v1/frozen-sandbox.json"])
  assets[path]=digest(await readFile(resolve(root,path)));
for(const [path,bytes]of Object.entries(source)){
  const target=resolve(destination,"source",path);await mkdir(dirname(target),{recursive:true});await writeFile(target,bytes);
}
const manifest={archivedAt:new Date().toISOString(),run,protocolFingerprint:report.metadata.protocolFingerprint,
  jarSha256:report.metadata.jarSha256,sourceFileSha256:report.metadata.sourceFileSha256,assetSha256:assets,
  note:"Baseline results remain at their original paths; this archive preserves the exact measured source bytes. Never overwrite this baseline with a rerun."};
await writeFile(resolve(destination,"manifest.json"),JSON.stringify(manifest,null,2)+"\n");
console.log(JSON.stringify({archived:run,sourceFiles:Object.keys(source).length,assetHashes:Object.keys(assets).length}));
