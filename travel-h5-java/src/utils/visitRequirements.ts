import type { VisitRequirement } from "../types";

export function parseVisitRequirements(text: string): VisitRequirement[] {
  const lines=text.split(/\r?\n/).map(line=>line.trim()).filter(Boolean);
  if(lines.length>100)throw new Error("最多填写100个景点的游览下限");
  const names=new Set<string>();
  return lines.map((line,index)=>{
    const match=line.match(/^(.+?)\s+(\d{1,3})\s*(?:分钟)?$/u);
    if(!match)throw new Error(`第${index+1}行请按“景点名称 180分钟”填写`);
    const placeName=match[1].trim(),minimumMinutes=Number(match[2]);
    if(placeName.length>120||minimumMinutes<15||minimumMinutes>360)
      throw new Error(`第${index+1}行名称最多120字，游览下限须为15～360分钟`);
    const key=placeName.replace(/[！-～]/gu,c=>String.fromCharCode(c.charCodeAt(0)-0xfee0)).replace(/\s/gu,"");
    if(names.has(key))throw new Error(`第${index+1}行景点重复，请合并游览下限`);
    names.add(key);return {placeName,minimumMinutes};
  });
}
