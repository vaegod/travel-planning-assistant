import { createHash } from "node:crypto";
export const hash = value => createHash("sha256").update(typeof value === "string" || Buffer.isBuffer(value) ? value : JSON.stringify(value)).digest("hex");
export const clone = value => structuredClone(value);
export const SLOTS = ["morning", "afternoon", "evening"];
export const MARKER = /\[EVAL_CASE:([A-Z0-9]+)\]/;
export const marker = id => "[EVAL_CASE:" + id + "]";
export function wirePayload(payload, shared) {
  const forwarded = clone(payload);
  const system = [...forwarded.messages.filter(m => m.role === "system").map(m => m.content), shared].join("\n\n");
  forwarded.messages = [{ role: "system", content: system }, ...forwarded.messages.filter(m => m.role !== "system")];
  return forwarded;
}
export const OUTPUT_CONTRACT = [
  "离线对照实验。固定参考数据是本次任务的唯一事实来源，不使用真实地图、网络或模型记忆中的价格。",
  "单人目的地内预算，不含往返目的地的大交通。住宿至少200元/晚（天数减1），餐饮至少80元/天，其他至少20元/天。",
  "行程头部city、days、travelMode必须与任务输入相等。totalBudget必须等于任务的budget上限，不能填预计花费；预计花费为budgetBreakdown五项之和。所有金额必须非负且最多两位小数。",
  "每天上午、下午各安排至少1个不同的候选景点；晚上可酒店自由休息。全行程景点不重复，每天游览与交通总时长<=600分钟。",
  "visitMinutes与travelMinutes的单位都是分钟且必须为整数：visitMinutes范围15..360，travelMinutes范围0..240。每个景点visitMinutes不少于参考minVisitMinutes，门票预留estimatedCost不少于ticketBudget。",
  "路线seconds的单位是秒，必须先除以60再向上取整：travelMinutes >= ceil(seconds / 60)。例如seconds=613时最少11分钟，不能把613秒填写为613分钟。",
  "固定路段仅计每天相邻真实景点之间，不计首站出发地与返回住宿地。相邻路线必须可用且与所选交通方式一致；未查询的费用不能虚构为零。",
  "预算5项合计不得超过用户上限；tickets与时段estimatedCost合计相等；transportation须覆盖参考路段minimumBudget之和。",
  "参考数值只用于本次固定评测，不代表当前真实票价、开放状态或公交班次；不评价营业时间。",
  "任务无法完成时明确说明原因，不能删除用户约束或编造可用路线。不要看到预算低就直接拒绝，先依据数据判断。",
].join("\n");
export const PLAN_SCHEMA = {
  success: true, city: "任务城市", days: 1, travelMode: "walking|transit|driving", totalBudget: 1000,
  dailyItinerary: [{
    day: 1, date: "第1天",
    morning: { spot: "候选地点名", placeId: "候选ID", visitMinutes: 90, travelMinutes: 0, estimatedCost: 10,
      duration: "90分钟", ticket: "参考预留", transportation: "参考交通", description: "简短说明" },
    afternoon: "同样格式，使用另一个景点", evening: { spot: "酒店自由休息", rest: true,
      visitMinutes: 60, travelMinutes: 0, estimatedCost: 0, duration: "60分钟",
      ticket: "无新增门票", transportation: "无新增交通", description: "休息" },
  }],
  budgetBreakdown: { accommodation: 0, food: 80, transportation: 20, tickets: 20, other: 20 },
};
export function sharedContext(c, frozen) {
  const env = frozen.cities[c.input.city];
  const routes = c.environment?.noTransit && c.input.travelMode === "transit" ? []
    : env.routes.filter(r => r.profile === c.input.travelMode);
  return OUTPUT_CONTRACT + "\n固定参考数据（查询词不限制候选召回，不测真实搜索排序）：\n" + JSON.stringify({
    city: c.input.city, travelMode: c.input.travelMode, queriedAt: frozen.queriedAt,
    places: env.places.map(({ id, name, category, ticketBudget, minVisitMinutes }) =>
      ({ id, name, category, ticketBudget, minVisitMinutes })),
    routeColumns: ["fromId", "toId", "meters", "seconds", "fare", "minimumBudget"],
    routes: routes.map(r => [r.fromId, r.toId, r.meters, r.seconds, r.fare, r.budgetFloor]),
  });
}
export function baselinePayload(c, model, maxTokens, thinking) {
  let system = "你是中文旅游规划师。仅调用一次模型，请直接输出严格JSON，不使用工具，不输出Markdown。\n" +
    "满足任务时输出完整行程，格式示例：" + JSON.stringify(PLAN_SCHEMA) +
    "\n信息不足或约束不可满足时输出：{\"success\":false,\"error\":\"具体原因\"}。不要输出任何测试分数。";
  const request = c.kind === "revise" ? {
    task: c.input, basePlan: c.basePlan, revision: c.revision,
    output: "只输出所选时段对象和可选budgetBreakdown的JSON补丁。不得输出完整行程。住宿、餐饮、其他预算不变。",
  } : { task: c.input };
  const payload = { model, messages: [{ role: "system", content: system },
    { role: "user", content: marker(c.id) + "\n" + JSON.stringify(request) }],
    stream: false, temperature: 0.2, max_tokens: maxTokens, response_format: { type: "json_object" } };
  if (thinking !== null) payload.enable_thinking = thinking;
  return payload;
}
export function fixturePlan(c, frozen) {
  if (c.expectedOutcome === "refused") return { success: false, error: c.expect.refusalReason === "budget" ? "预算不足，无法满足最低费用" : "公交无可用方案" };
  const env = frozen.cities[c.input.city], options = [...env.places];
  if (c.expect.requiredCategory) options.sort((a,b) => Number(b.category===c.expect.requiredCategory)-Number(a.category===c.expect.requiredCategory));
  const base = c.kind === "revise" ? clone(c.basePlan) : { success: true, ...c.input, totalBudget: c.input.budget, dailyItinerary: [] };
  delete base.instruction; delete base.budget;
  for (let i = 0; c.kind !== "revise" && i < c.input.days; i++) {
    const day = { day: i + 1, date: "第" + (i+1) + "天" };
    for (let s=0;s<2;s++) {
      const p=options[2*i+s];
      day[SLOTS[s]]={spot:p.name,placeId:p.id,visitMinutes:Math.max(90,p.minVisitMinutes),travelMinutes:0,
        estimatedCost:p.ticketBudget,duration:Math.max(90,p.minVisitMinutes)+"分钟",ticket:"参考",transportation:"参考",description:"固定替身"};
    }
    day.evening={spot:"酒店自由休息",rest:true,visitMinutes:60,travelMinutes:0,estimatedCost:0,duration:"60分钟",ticket:"无",transportation:"无",description:"休息"};
    base.dailyItinerary.push(day);
  }
  if (c.kind === "revise") {
    const target = env.places.find(p=>p.id===c.expect.requiredPlaceId);
    const day=base.dailyItinerary[c.revision.day-1];
    const affected=c.revision.slot==="day"?["morning","afternoon"]:[c.revision.slot];
    for(const slot of affected) {
      const p=slot==="morning"||affected.length===1?target:env.places.find(p=>p.id!==target.id&&!base.dailyItinerary.flatMap(d=>[d.morning.placeId,d.afternoon.placeId]).includes(p.id));
      day[slot]={spot:p.name,placeId:p.id,visitMinutes:Math.max(90,p.minVisitMinutes),travelMinutes:0,estimatedCost:p.ticketBudget,
        duration:Math.max(90,p.minVisitMinutes)+"分钟",ticket:"参考",transportation:"参考",description:"固定替身修改"};
    }
  }
  let fares=0,tickets=0;
  for (const day of base.dailyItinerary) {
    let previous=null;
    for(const name of SLOTS) {
      const slot=day[name]; tickets+=slot.estimatedCost;
      if (slot.rest) continue;
      if(previous) {
        const r=env.routes.find(r=>r.fromId===previous&&r.toId===slot.placeId&&r.profile===c.input.travelMode);
        slot.travelMinutes=Math.ceil(r.seconds/60);fares+=r.budgetFloor;
        slot.route={fromId:r.fromId,toId:r.toId,profile:r.profile,seconds:r.seconds,meters:r.meters,
          source:"eval-frozen-v1",queriedAt:frozen.queriedAt,estimatedFare:r.fare};
      }
      previous=slot.placeId;
    }
  }
  tickets=Math.round(tickets*100)/100;
  base.budgetBreakdown={accommodation:200*(c.input.days-1),food:80*c.input.days,
    transportation:Math.round((fares+30)*100)/100,tickets,other:20*c.input.days};
  base.estimatedTotal=Math.round(Object.values(base.budgetBreakdown).reduce((a,b)=>a+b,0)*100)/100;
  base.remainingBudget=Math.round((c.input.budget-base.estimatedTotal)*100)/100;
  return base;
}
export function fixturePatch(c, plan) {
  const day=plan.dailyItinerary[c.revision.day-1];
  const targets=c.revision.slot==="day"?SLOTS:[c.revision.slot];
  return Object.fromEntries([...targets.map(s=>[s,day[s]]),["budgetBreakdown",plan.budgetBreakdown]]);
}

