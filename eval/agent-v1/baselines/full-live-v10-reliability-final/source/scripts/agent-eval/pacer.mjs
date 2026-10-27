// Shared account budget: queue new calls, never retry a scored request.
export class RequestPacer {
  constructor({tokenBudget=40000,windowMs=60000,minIntervalMs=5000,now=Date.now,sleep=abortableSleep}={}){
    if(!(tokenBudget>0&&windowMs>0&&minIntervalMs>=0))throw Error("Invalid pacing configuration");
    Object.assign(this,{tokenBudget,windowMs,minIntervalMs,now,sleep});
    this.entries=[];this.lastStart=-Infinity;this.cooldownUntil=0;this.tail=Promise.resolve();
  }
  acquire(reserve,signal){
    if(!(reserve>0&&reserve<=this.tokenBudget))return Promise.reject(Error("Request reservation exceeds pacing budget"));
    const entered=this.now();
    const pending=this.tail.then(async()=>{
      for(;;){
        signal?.throwIfAborted();
        const at=this.now();
        this.entries=this.entries.filter(e=>e.at+this.windowMs>at);
        const used=this.entries.reduce((n,e)=>n+e.tokens,0);
        const tokenWait=used+reserve>this.tokenBudget?this.entries[0].at+this.windowMs-at:0;
        const wait=Math.max(0,tokenWait,this.lastStart+this.minIntervalMs-at,this.cooldownUntil-at);
        if(wait>0){await this.sleep(wait,signal);continue;}
        const entry={at,tokens:reserve};this.entries.push(entry);this.lastStart=at;
        return {waitMs:at-entered,complete:actual=>{
          if(Number.isFinite(actual)&&actual>=0)entry.tokens=actual;
        }};
      }
    });
    this.tail=pending.catch(()=>{});
    return pending;
  }
  rateLimited(){this.cooldownUntil=Math.max(this.cooldownUntil,this.now()+65000);}
}
function abortableSleep(ms,signal){
  return new Promise((resolve,reject)=>{
    signal?.throwIfAborted();
    const finish=()=>{signal?.removeEventListener("abort",cancel);resolve();};
    const timer=setTimeout(finish,ms);
    function cancel(){clearTimeout(timer);signal?.removeEventListener("abort",cancel);reject(signal.reason);}
    signal?.addEventListener("abort",cancel,{once:true});
  });
}
