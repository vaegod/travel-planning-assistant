import type { KnowledgeImport } from './knowledge';
export interface KnowledgePack {format:'travel-knowledge/v1';title:string;license:string;notice:string;documents:KnowledgeImport[]}
export function decodeKnowledgePack(name:string, bytes:Uint8Array):KnowledgePack {
  if (!/\.json$/i.test(name) || bytes.byteLength > 16*1024*1024) throw Error('请选择16MB以内的JSON资料包');
  let root:unknown;
  try {root=JSON.parse(new TextDecoder('utf-8',{fatal:true}).decode(bytes));}catch{throw Error('资料包必须是UTF-8编码的有效JSON');}
  if (!root || typeof root !== 'object') throw Error('资料包格式不正确');
  const pack=root as Record<string,unknown>;
  if (pack.format !== 'travel-knowledge/v1' || !Array.isArray(pack.documents) || !pack.documents.length || pack.documents.length > 1000) throw Error('需要travel-knowledge/v1格式，含1至1000份资料');
  const string=(v:unknown,min:number,max:number,label:string,multiline=false)=>{if(typeof v!=='string'||v.trim().length<min||v.length>max||(multiline?/[\x00-\x08\x0b\x0c\x0e-\x1f]/:/[\x00-\x1f]/).test(v))throw Error(label+'不符合长度或文本要求');return v.trim();};
  const date=(v:unknown)=>{if(v==null||v==='')return null;if(typeof v!=='string'||!/^\d{4}-\d{2}-\d{2}$/.test(v)||Number.isNaN(Date.parse(v))||new Date(v).toISOString().slice(0,10)!==v)throw Error('资料日期格式不正确');return v;};
  const documents=pack.documents.map((raw:unknown,index:number)=>{
    if(!raw||typeof raw!=='object')throw Error(`第${index+1}份资料格式不正确`);
    const d=raw as Record<string,unknown>, url=string(d.sourceUrl??'',0,1000,'来源链接');
    if(url){let u:URL;try{u=new URL(url);}catch{throw Error('来源链接不正确');}if(u.protocol!=='https:'||u.username||u.password||u.hash)throw Error('来源需为不含账号或锚点的HTTPS链接');}
    const from=date(d.validFrom),to=date(d.validTo);
    if(from&&to&&from>to)throw Error('资料有效期起止顺序不正确');
    return {title:string(d.title,1,160,'资料标题'),city:string(d.city,1,50,'资料城市'),content:string(d.content,30,12000,'资料正文',true),sourceUrl:url,publishedOn:date(d.publishedOn),validFrom:from,validTo:to};
  });
  return {format:'travel-knowledge/v1',title:typeof pack.title==='string'?pack.title.slice(0,200):name,license:typeof pack.license==='string'?pack.license.slice(0,200):'未标明，导入前请核对来源许可',notice:typeof pack.notice==='string'?pack.notice.slice(0,1000):'',documents};
}
