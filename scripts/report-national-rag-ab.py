"""Build the report from immutable raw answers and explicit per-answer review.
No provider calls and no scoring by keyword count. matplotlib is only used for export.
"""
import collections
import datetime as dt
import hashlib
import json
import math
import statistics as stats
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
DOCS=ROOT/'docs'
raw_bytes=(DOCS/'全国RAG开关对比原始结果.json').read_bytes()
run=json.loads(raw_bytes)
review=json.loads((ROOT/'eval/national-rag-ab-review.json').read_text(encoding='utf-8'))
trace=json.loads((DOCS/'全国RAG生成调用追踪.json').read_text(encoding='utf-8'))
diagnosis=json.loads((DOCS/'全国RAG检索诊断.json').read_text(encoding='utf-8'))
assert run['completed'] and len(run['results'])==80
assert hashlib.sha256(raw_bytes).hexdigest()==review['rawResultsSha256']
reviews={r['key']:r for r in review['rows']}
assert len(reviews)==80 and all(row['key'] in reviews for row in run['results'])
assert len({row['key'] for row in run['results']})==80
rows=run['results']
for c in run['dataset']['cases']:
 for rep in [1,2]:
  pair=[r for r in rows if r['caseId']==c['id'] and r['repetition']==rep]
  assert len(pair)==2 and {r['ragEnabled'] for r in pair}=={True,False}
  assert pair[0]['submittedQuestion']==pair[1]['submittedQuestion'] and pair[0]['city']==pair[1]['city']

def percentile(values,p):
 values=sorted(values);return values[max(0,math.ceil(len(values)*p)-1)]
def summary(enabled):
 selected=[r for r in rows if r['ragEnabled']==enabled]
 facts=[r for r in selected if r['category']=='fact']
 calls=[c for c in trace['calls'] if c['requestId'] in {r['requestId'] for r in selected}]
 count=lambda field:dict(collections.Counter(reviews[r['key']][field] for r in facts))
 result={'requests':len(selected),'errors':sum(r['status']!='completed' for r in selected),
  'factualAnswers':len(facts),'targetGrades':count('targetGrade'),'wholeAnswerGrades':count('answerGrade'),
  'medianTotalMs':stats.median(r['totalMs'] for r in selected),
  'p95TotalMs':percentile([r['totalMs'] for r in selected],.95),'maxTotalMs':max(r['totalMs'] for r in selected),
  'medianFirstContentMs':stats.median(r['firstContentMs'] for r in selected),
  'p95FirstContentMs':percentile([r['firstContentMs'] for r in selected],.95),
  'medianAnswerCharacters':stats.median(r['answerCharacters'] for r in selected),
  'modes':dict(collections.Counter(r['evidence']['mode'] for r in selected)),
  'answersWithCitations':sum(bool(r['evidence']['sources']) for r in selected),
  'citations':sum(r['citationIntegrity']['citations'] for r in selected),
  'exactQuotesValid':sum(r['citationIntegrity']['exactQuotesValid'] for r in selected),
  'claimsSupported':sum(x['support']=='supported' for r in selected for x in reviews[r['key']]['citationReviews']),
  'claimsReviewed':sum(len(reviews[r['key']]['citationReviews']) for r in selected),
  'generationCalls':len(calls),'reportedInputTokens':sum(c['inputTokens']or 0 for c in calls),
  'reportedOutputTokens':sum(c['outputTokens']or 0 for c in calls),
  'usageCompleteCalls':sum(c['usageComplete'] for c in calls),
  'callsPerRequest':dict(collections.Counter(collections.Counter(c['requestId'] for c in calls).values())),
  'privateAnswers':sum(reviews[r['key']].get('privateFactAnswered',False) for r in selected),
  'boundaryPass':sum(reviews[r['key']].get('appropriateBoundaryBehavior',False) for r in selected),
  'openFormalPass':sum(reviews[r['key']].get('formalConstraintPass',False) for r in selected)}
 disputed=set(review['sensitivityDisputedExtraKeys'])
 result['wholeCorrectIgnoringDisputedExtras']=sum(reviews[r['key']]['answerGrade']=='C' or (r['key'] in disputed and reviews[r['key']]['targetGrade']=='C') for r in facts)
 return result
off,on=summary(False),summary(True)
assert off['factualAnswers']==on['factualAnswers']==28
metric={'date':'2026-10-18','method':'实际应用开关、同题同城市、两轮反转顺序、小样本单助手审阅、非独立盲评',
 'off':off,'on':on,'dataSha256':run['datasetSha256'],'rawSha256':review['rawResultsSha256'],
 'referenceReviewSha256':hashlib.sha256((ROOT/'eval/national-rag-ab-review.json').read_bytes()).hexdigest()}
(DOCS/'全国RAG开关对比指标.json').write_text(json.dumps(metric,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def pct(n,d):return f'{n}/{d}（{100*n/d:.1f}%）'
def sec(ms):return f'{ms/1000:.2f}'
def cell(value):return str(value).replace('|','\\|').replace('\n',' ')
def local_time(value):return dt.datetime.fromisoformat(value.replace('Z','+00:00')).astimezone(dt.timezone(dt.timedelta(hours=8))).strftime('%Y-%m-%d %H:%M:%S +08:00')
def review_pair(c,enabled,field):return ' / '.join(reviews[r['key']].get(field,'—') for r in sorted([r for r in rows if r['caseId']==c['id'] and r['ragEnabled']==enabled],key=lambda r:r['repetition']))

lines=[
 '# 当前全国知识库：开启与关闭RAG的真实对比报告','',
 '## 结论','',
 '**有实际收益，但当前收益主要是来源可追溯和私人资料可答；没有足够证据证明全国旅游事实问答普遍更准。默认开启还会增加等待和调用次数。**','',
 f'本次20道问题、每题每种模式2次，共80份正式回答，另有2次预热。使用wl现有545份资料、3087个片段，没有为评测补充知识、修改业务提示词或调整检索参数。',
 f'核心事实完整命中：关闭{pct(off["targetGrades"].get("C",0),28)}，开启{pct(on["targetGrades"].get("C",0),28)}；损失集中在苏州园林扩展年份题的两次不完整回答。',
 f'把明确错误的附加背景也计入时，整段完整正确为关闭{pct(off["wholeAnswerGrades"].get("C",0),28)}、开启{pct(on["wholeAnswerGrades"].get("C",0),28)}，只相差1份回答。这是本助手按公开标准审阅的小样本结果，不能当作通用准确率或显著改善。','',
 '建议保留RAG用于有来源的资料问答、上传笔记；通用出行建议不宜无差别先试一次资料生成。优先修复目标事实缺失和检索片段遗漏，再做新的留出题评估。','',
 '## 主要结果','',
 '| 指标 | 关闭RAG | 开启RAG | 解读 |','| --- | ---: | ---: | --- |',
 f'| 公开事实：目标要点完整正确 | {pct(off["targetGrades"].get("C",0),28)} | {pct(on["targetGrades"].get("C",0),28)} | 核心问题并未整体改善 |',
 f'| 公开事实：整段完整正确，含附述审阅 | {pct(off["wholeAnswerGrades"].get("C",0),28)} | {pct(on["wholeAnswerGrades"].get("C",0),28)} | 仅增加1条，口径敏感 |',
 f'| 公开事实：部分回答 | {off["wholeAnswerGrades"].get("P",0)}/28 | {on["wholeAnswerGrades"].get("P",0)}/28 | 不能把有引用的半个答案算完整成功 |',
 f'| 公开事实：含确认的事实错误 | {off["wholeAnswerGrades"].get("W",0)}/28 | {on["wholeAnswerGrades"].get("W",0)}/28 | 含额外补充内容的确认错误 |',
 f'| 最终附结构化引用的回答 | {pct(off["answersWithCitations"],40)} | {pct(on["answersWithCitations"],40)} | 产品层可追溯性提升 |',
 f'| 引文ref/逐字片段/本地chunk核对 | 不适用 | {on["exactQuotesValid"]}/{on["citations"]} | 只保证引用定位，不自动保证语义 |',
 f'| 实质结论的引文支持，逐条审阅 | 不适用 | {on["claimsSupported"]}/{on["claimsReviewed"]} | 两条苏州回答仍缺扩展年份 |',
 f'| 虚构私人笔记题可回答 | {off["privateAnswers"]}/2 | {on["privateAnswers"]}/2 | 非公共旅游事实，不计准确率分母 |',
 f'| 一般缺资料/实时边界题，审阅通过 | {off["boundaryPass"]}/4 | {on["boundaryPass"]}/4 | 小样本；不代表能查询实时余票 |',
 f'| 开放式建议的显式条件检查 | {off["openFormalPass"]}/6 | {on["openFormalPass"]}/6 | 只看文本落实，不等于现场可执行性 |',
 f'| 接口/流式失败 | {off["errors"]}/40 | {on["errors"]}/40 | 评测脚本不自动补跑失败请求 |','',
 '私有笔记编号与集合点是虚构诊断数据，关闭RAG无法访问时拒答不记作事实错误。结构化来源为当前应用专有输出格式，关闭路径没有该格式，并不意味着模型永远不能在文本中给出真实链接。','',
 '![结果与等待时间](全国RAG开关对比图.png)','',
 '## 响应速度与调用开销','',
 '| 指标 | 关闭RAG | 开启RAG |','| --- | ---: | ---: |',
 f'| 首段正文可见中位数 | {sec(off["medianFirstContentMs"])}秒 | {sec(on["medianFirstContentMs"])}秒 |',
 f'| 首段正文可见P95 | {sec(off["p95FirstContentMs"])}秒 | {sec(on["p95FirstContentMs"])}秒 |',
 f'| 完整回答结束中位数 | {sec(off["medianTotalMs"])}秒 | {sec(on["medianTotalMs"])}秒 |',
 f'| 完整回答结束P95 | {sec(off["p95TotalMs"])}秒 | {sec(on["p95TotalMs"])}秒 |',
 f'| 最慢一条完整回答 | {sec(off["maxTotalMs"])}秒 | {sec(on["maxTotalMs"])}秒 |',
 f'| 正文长度中位数，不计引文元数据 | {off["medianAnswerCharacters"]}字符 | {on["medianAnswerCharacters"]}字符 |',
 f'| 正式请求对应的生成调用 | {off["generationCalls"]}次 | {on["generationCalls"]}次 |',
 f'| 生成模型返回的输入Token合计 | {off["reportedInputTokens"]} | {on["reportedInputTokens"]} |',
 f'| 生成模型返回的输出Token合计 | {off["reportedOutputTokens"]} | {on["reportedOutputTokens"]} |','',
 f'本次开启后首段等待中位数约为关闭的{on["medianFirstContentMs"]/off["medianFirstContentMs"]:.2f}倍，完整结束中位数增加{(on["medianTotalMs"]/off["medianTotalMs"]-1)*100:.1f}%。双方都出现约9.56秒长尾，保留在统计中，没有删除慢样本。','',
 f'调用日志显示：22/40个开启请求先做一次资料JSON生成，资料不足后又做一次普通生成；其余18个只调用一次。总计{on["generationCalls"]}次对{off["generationCalls"]}次，增加{(on["generationCalls"]/off["generationCalls"]-1)*100:.1f}%。这是实际回退链路的开销，不是80个用户请求必然只对应80次模型API调用。',
 '资料回答需要生成完整JSON、核对引文，再发出正文；关闭或回退路径直接流式输出。首段时间差同时包含这一产品设计差异。Token仅统计生成模型实际返回用量，不含向量查询，不按Token直接换算费用，也没有剥离外部服务排队、网络和缓存因素。','',
 '计时从发送POST /api/travel/chat前开始，到SSE done结束；首段只计正文chunk，不把start、心跳或来源元数据算首字。计时不含登录、建会话、导入和事后引用复核。P95使用nearest-rank，样本小，不是SLA。','',
 '## 开启开关后实际走了什么路径','',
 '| 路径 | 次数 / 40 |','| --- | ---: |',
 f'| 真正按知识库回答 KNOWLEDGE | {on["modes"].get("KNOWLEDGE",0)} |',
 f'| 资料不足或实时限制后由模型补充 MODEL_INSUFFICIENT | {on["modes"].get("MODEL_INSUFFICIENT",0)} |',
 f'| 未匹配到城市资料，模型补充 MODEL_NO_MATCH | {on["modes"].get("MODEL_NO_MATCH",0)} |','',
 '14道公开事实题中，仅6道题的两轮回答使用了引用，其中苏州题只答了一半。3道开放式建议题全部回退到模型；本次没有观察到RAG为这类建议提供稳定的新信息。静态实时意图规则会直接限制票务问题，不应把它算作检索召回成功。','',
 '## 具体收益与失败案例','',
 '- **减少不必要的错误扩写**：西安古称题关闭时，分别出现“自西周沿用长安”“20世纪50年代才改名西安”的附加错误；开启时简短回答长安并给出原文。南京题关闭一次将明孝陵混为明十三陵的一部分，开启两次都只给出资料支持的对象。',
 '- **现有语料解决不了地址错配**：茶叶博物馆题，两模式四份答案都没有正确给出“双峰→龙井路88号、龙井→翁家山268号”。当前全国包没有这组详细地址，开启后仍回退并答错。没有为了改善结果临时导入已有的20份杭州官方摘要。',
 '- **保守但不完整**：苏州题开启两次都引用1997年，却没有回答扩展的2000年。关闭两次的目标年份正确，但补充的首批/扩展园林数量或对象有错误。引用有效和回答完整必须分别衡量。',
 '- **回退后仍会有幻觉**：泉州双塔题一条回退回答把两塔都说成唐代始建；另一条给出不符官方的西塔高度。敦煌一条回退把窟群延展方向写成东西。来源标记诚实，不代表模型补充内容已经核实。',
 '- **私人资料有明确价值**：虚构笔记题关闭两次都无法给出编号与地点；开启两次都给出蓝鲸9017和松林书角，并引用本账号上传的原文。这是资料可答性的提升，不作为公共旅游准确率加分。',
 '- **开放建议仍需检查实际约束**：适老题一条开启后的回退方案只说两地步行平缓，并没有安排接驳或实质减少行走。所列电梯、电瓶车、场馆开放条件未现场核实，不能凭文字条件检查就保证可执行性。','',
 '## 语料和检索诊断：为什么很多题回退','',
 '下列诊断在全部计时回答完成后单独执行，没有混入耗时统计。它复用同一问题、城市与Top-K；关键词只帮助定位，不当作严格Recall@K。','',
 '| 现象 | 证据与判断 |','| --- | --- |',
 '| 博物馆地址、苏州扩展年、泉州双塔名、重庆两江、布宫山名信息不完整 | 当前一城一篇节选缺少这些目标事实；文档数量多不等于具体FAQ有资料 |',
 '| 敦煌：原文有鸣沙山东麓，但返回片段没有 | 目标句落在未选中的相邻片段；返回的一段还混入较长归属/许可尾注，属于可定位的检索遗漏 |',
 '| 北京：完整文档有端点句，但返回段主要列经过地及不同中轴分区 | 名称关键词都在，不代表端点关系已召回；不能靠关键词判定问题可答 |',
 '| 青岛：全文虽有中山路，但关联劈柴院等条目；栈桥条目只写太平路/青岛湾 | 属于目标关系缺口，不能因中山路一词出现就认定语料可答 |',
 '| 大理：片段已有崇圣寺三塔景区名称，仍两次回退 | 可能是关系表述/严格提示约束导致的保守拒答；还需单独消融，未在本次修改代码验证因果 |','',
 '当前每个文档最多返回2个片段，而全国包按一城一篇组织；所以Top-K=4并不意味着同一城市一定能拿到4段上下文。这会与固定450字符切片共同影响信息完整性。详情见[检索诊断](全国RAG检索诊断.json)。','',
 '优先改进方向：补充核对过的官方场馆FAQ和关系字段；按主题切片并补相邻段，避免版权尾注进入检索正文；考虑实体关键词与向量混合重排；先判断资料是否足以覆盖问题，减少“资料试答一次再模型补充一次”；如允许部分模型补充，必须分别标来源，不能把补充内容伪装成引用事实。上述仅为建议，本次没有边测边改。','',
 '## 评分口径与敏感性','',
 'C=完整正确且未确认明显错误附述；P=缺必要要点但未见明确错误；W=有明确事实错误；A=未实质回答；E=接口错误。主表按全部28份公共事实回答作分母，不因拒答移除样本。另给只核问题目标要点的口径，避免把“少说一些”直接当核心答题能力提升。','',
 f'对三条有措辞/约数/湾区范围解释空间的附述，若一律不扣确定错误分，整段C变为关闭{pct(off["wholeCorrectIgnoringDisputedExtras"],28)}、开启{pct(on["wholeCorrectIgnoringDisputedExtras"],28)}；这仍不能证明稳定的泛化提升。逐条评分及未核实细节保存在[逐条审阅记录](../eval/national-rag-ab-review.json)。',
 '没有充分核实的额外门牌、山名词源、笼统历史描述和设施情况，没有一律判错来抬高差距，而是记作风险。评分由本对话助手完成，没有独立评委或盲评；两次重复也不是独立随机人群样本，不给显著性、置信区间或全国准确率承诺。','',
 '## 同题逐项结果','',
 'C/P/W按上述口径。每格分号前为两轮目标评分，分号后为两轮整段评分，各组按第1轮 / 第2轮排列；总耗时为该题两次中位数。','',
 '| 题号 | 问题 | 关闭：目标 / 整段 | 开启：目标 / 整段 | 开启路径 | 完整耗时秒：关→开 |','| --- | --- | --- | --- | --- | --- |'
]
for c in run['dataset']['cases']:
 a=[r for r in rows if r['caseId']==c['id'] and not r['ragEnabled']]
 b=[r for r in rows if r['caseId']==c['id'] and r['ragEnabled']]
 grades=lambda enabled: f'{review_pair(c,enabled,"targetGrade")}；{review_pair(c,enabled,"answerGrade")}' if c['category']=='fact' else '单列场景检查'
 lines.append(f'| {c["id"]} | {cell(c["question"])} | {grades(False)} | {grades(True)} | {" / ".join(sorted(set(r["evidence"]["mode"] for r in b)))} | {sec(stats.median(r["totalMs"] for r in a))} → {sec(stats.median(r["totalMs"] for r in b))} |')
lines += ['', '## 实验方法、限制与复现','',
 f'- 运行窗口（客户端UTC+08:00）：{local_time(run["startedAt"])} 至 {local_time(run["completedAt"])}。',
 '- 使用真实Qwen/Qwen3.5-35B-A3B，非思考模式；向量BAAI/bge-m3，1024维。',
 '- 20题：14个公共事实题、3个开放建议题、2个边界题、1个虚构私人资料诊断题。没有把私人题或实时拒答混进公共事实准确率。',
 '- 每题开关各2次，每次新建空白会话，问题、附加120字要求和城市相同。题序固定种子20261018随机打乱；同题先后顺序第二轮反转。种子只控制请求顺序，不控制模型生成随机性。',
 '- 当前应用开启资料生成使用JSON、temperature=0.2、最多2048输出Token；关闭和回退使用流式、temperature=0.7、最多4096输出Token。故这是用户实际开关效果的系统对比，不能归因成只增加检索上下文的严格消融。',
 '- 包含少量此前验收用过的题，数据集中逐项标明；不宣称独立留出集。结果可能受模型已有知识、提示词、语料截断、外部服务负载和审阅者判断影响。',
 '- 语料、模型配置和业务代码没有为本次结果调整；运行前后校验545份资料元数据指纹一致，原4份行程和原4个会话元数据保留。评测新增82个按题号标记的会话（含2次预热），总86个，未自动删除测试会话。',
 f'- 数据集SHA-256：`{run["datasetSha256"]}`；原始结果SHA-256：`{review["rawResultsSha256"]}`。',
 '- 不需要为这次只新增评测脚本和文档的工作重复执行产品单元测试；本次实测80次正式请求0失败，引用通过真实资料详情API回查。',
 '', '复现真实请求（会调用已配置模型、建立标记会话）：','',
 '    node scripts/evaluate-national-rag-ab.mjs --live','',
 '默认检查点用于断点续跑，不会把已记录请求再发一遍。新一轮应使用新的EVAL_CHECKPOINT和EVAL_EXPORT路径。新输出必须重新审阅，不能套用本轮绑定原始结果哈希的评分。例如在PowerShell先设置：',
 '', "    $env:EVAL_CHECKPOINT='.runtime/national-rag-ab-run-new.json'", "    $env:EVAL_EXPORT='docs/全国RAG开关新一轮原始结果.json'", '',
 '', '离线重建本文与图表：','',
 '    python scripts/report-national-rag-ab.py','', '离线图表脚本需要Python与matplotlib，不属于应用运行依赖。', '',
 '完整问答及逐条评阅见[问答对照附录](全国RAG开关问答对照.md)；全部返回、引用与片段快照见[原始结果](全国RAG开关对比原始结果.json)；汇总见[指标JSON](全国RAG开关对比指标.json)；62/40次调用及用量见[生成调用追踪](全国RAG生成调用追踪.json)。凭据、Cookie和API Key没有写入报告。','',
 '## 标准答案的外部核对来源','',
 '| 题号 | 参考事实 | 核对来源 |','| --- | --- | --- |']
for c in run['dataset']['cases']:
 if c['category']=='fact':
  refs='；'.join(f'[{ref["title"]}]({ref["url"]})' for ref in c['references'])
  lines.append(f'| {c["id"]} | {cell(c["expected"])} | {refs} |')
lines+=['','附加错误核对还使用了馆方官网、苏州园林管理部门、泉州市政府、敦煌研究院及UNESCO相关页面；详见审阅记录中的supplementaryReferences。来源核对只证明所列最小事实，并不保证全部附加出行建议实时有效。知识库引用原文来自已导入、保留原页与许可信息的维基导游节选；私人诊断来源为明确标注虚构的上传笔记。','']
(DOCS/'全国RAG开关对比评估报告.md').write_text('\n'.join(lines),encoding='utf-8')

appendix=['# 全国RAG开关：全部问答与逐条评阅','','以下保留真实模型输出供对比审阅，包含已标注的错误内容，不作为出行建议。两轮均按相同问题和城市调用。','']
for c in run['dataset']['cases']:
 appendix += [f'## {c["id"]} · {c["city"]}', '', c['question'], '', '评分要求：'+c['rubric']]
 if c.get('expected'):appendix+=['','参考要点：'+c['expected']]
 for rep in [1,2]:
  appendix+=['',f'### 第{rep}轮','']
  for enabled in [False,True]:
   r=next(r for r in rows if r['caseId']==c['id'] and r['repetition']==rep and r['ragEnabled']==enabled);v=reviews[r['key']]
   appendix += [f'**{"开启" if enabled else "关闭"}RAG** · {r["evidence"]["mode"]} · 首段{sec(r["firstContentMs"])}秒 / 完整{sec(r["totalMs"])}秒','']
   appendix += ['> '+line for line in r['answer'].strip().splitlines()]
   appendix += ['',f'审阅：{v.get("targetGrade","场景检查")}（目标） / {v.get("answerGrade","不计公开事实分")}（整段）。'+v['notes']]
   if v.get('criteria'):appendix+=['','形式条件：'+json.dumps(v['criteria'],ensure_ascii=False)]
   for source in r['evidence']['sources']:
    appendix += ['',f'来源{source["ref"]}：{source["title"]} · {source["city"]}']
    if source['sourceUrl']:appendix += [f'[原页固定版本]({source["sourceUrl"]})']
    for claim in r['evidence']['claims']:
     for citation in claim['citations']:
      if citation['ref']==source['ref']:appendix += ['','逐字引文：','', '> '+citation['quote'].replace('\n','\n> ')]
   if not r['evidence']['sources']:appendix+=['','本轮没有知识库引用。']
appendix+=['','各条知识库引文保留其原页来源；公开引文为CC BY-SA 4.0维基导游文本的项目清洗节选，作者历史见原页。虚构私人笔记仅用于诊断。完整来源片段与本地chunk核对记录在原始JSON中。','']
(DOCS/'全国RAG开关问答对照.md').write_text('\n'.join(appendix),encoding='utf-8')

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.font_manager import FontProperties
font_path=Path('C:/Windows/Fonts/msyh.ttc')
if font_path.exists():
 plt.rcParams['font.family']=FontProperties(fname=str(font_path)).get_name()
plt.rcParams['axes.unicode_minus']=False
plt.rcParams['font.size']=10
fig,axes=plt.subplots(1,3,figsize=(15,4.6),layout='constrained')
colors=['#e49a49','#2763dc'];width=.33
for arm,s in enumerate([off,on]):
 xs=[-.18+arm*.36,.82+arm*.36]
 vals=[s['targetGrades'].get('C',0)/28,s['wholeAnswerGrades'].get('C',0)/28]
 axes[0].bar(xs,vals,width,color=colors[arm],label=['关闭RAG','开启RAG'][arm])
 for x,y in zip(xs,vals):axes[0].text(x,y+.02,f'{100*y:.1f}%',ha='center',fontsize=9)
 vals2=[s['medianFirstContentMs']/1000,s['medianTotalMs']/1000]
 axes[1].bar(xs,vals2,width,color=colors[arm])
 for x,y in zip(xs,vals2):axes[1].text(x,y+.05,f'{y:.2f}',ha='center',fontsize=9)
axes[0].set(xticks=[0,1],xticklabels=['目标事实完整正确','含附述的整段审阅'],ylim=(0,1.1),title='事实题：每模式28份回答')
axes[0].legend(frameon=False,loc='upper right',fontsize=9)
axes[0].set_yticks([0,.25,.5,.75,1],['0%','25%','50%','75%','100%'])
axes[1].set(xticks=[0,1],xticklabels=['首段正文','完整结束'],ylabel='秒',ylim=(0,3.35),title='可见等待时间中位数')
axes[2].bar([0,1],[off['answersWithCitations'],on['answersWithCitations']],color=colors,width=.55)
for x,s in enumerate([off,on]):axes[2].text(x,s['answersWithCitations']+.8,f'{s["answersWithCitations"]}/40',ha='center')
axes[2].set(xticks=[0,1],xticklabels=['关闭RAG','开启RAG'],ylim=(0,43),ylabel='回答数量',title='最终带可核对引用的回答')
for ax in axes:
 ax.spines[['top','right']].set_visible(False);ax.set_axisbelow(True);ax.grid(axis='y',alpha=.18)
fig.suptitle('当前应用开关对比：20题 × 2轮 × 2模式 = 80份回答',fontsize=14,fontweight='bold')
fig.get_layout_engine().set(rect=(0,.10,1,.82))
fig.text(.5,.015,'小样本、单助手审阅；提示词、温度和输出形式不同；不能作为普遍准确率或SLA。',ha='center',fontsize=9,color='#64748b')
fig.savefig(DOCS/'全国RAG开关对比图.png',dpi=160)
fig.savefig(DOCS/'全国RAG开关对比图.svg')
plt.close(fig)
print(json.dumps({'off':off,'on':on},ensure_ascii=False))
