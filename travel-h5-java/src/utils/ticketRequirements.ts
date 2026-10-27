import type { TicketRequirement } from "../types";

export function parseTicketRequirements(text: string): TicketRequirement[] {
  const lines=text.split(/\r?\n/).map(line=>line.trim()).filter(Boolean);
  if(lines.length>100)throw new Error("最多填写100个景点的门票预留参考");
  const names=new Set<string>();
  return lines.map((line,index)=>{
    const match=line.match(/^(.+?)\s+(\d+(?:\.\d{1,2})?)\s*元?$/u);
    if(!match)throw new Error(`第${index+1}行请按“景点名称 98元”填写，金额最多两位小数`);
    const placeName=match[1].trim(),minimumBudget=Number(match[2]);
    if(placeName.length>120||!Number.isFinite(minimumBudget)||minimumBudget>1000000)
      throw new Error(`第${index+1}行名称最多120字，金额须为0～1000000元`);
    const key=placeName.replace(/[！-～]/gu,c=>String.fromCharCode(c.charCodeAt(0)-0xfee0)).replace(/\s/gu,"");
    if(names.has(key))throw new Error(`第${index+1}行景点重复，请合并门票预留参考`);
    names.add(key);
    return {placeName,minimumBudget,source:"用户提供的门票预算参考"};
  });
}
