export function validChatEvidence(value) {
  if (!value || typeof value !== 'object' || !['KNOWLEDGE','KNOWLEDGE_UNAVAILABLE','MODEL_ONLY','MODEL_NO_MATCH','MODEL_INSUFFICIENT','MODEL_RETRIEVAL_ERROR'].includes(value.mode)
      || typeof value.city !== 'string' || typeof value.notice !== 'string' || !Array.isArray(value.sources) || value.sources.length > 4 || !Array.isArray(value.claims) || value.claims.length > 4) return false;
  if (value.mode !== 'KNOWLEDGE') return value.sources.length === 0 && value.claims.length === 0;
  const refs = new Set();
  for (const source of value.sources) {
    if (!source || typeof source.ref !== 'string' || refs.has(source.ref) || typeof source.title !== 'string' || typeof source.content !== 'string' || typeof source.sourceUrl !== 'string') return false;
    refs.add(source.ref);
  }
  return value.sources.length > 0 && value.claims.length > 0 && value.claims.every(claim => typeof claim.text === 'string' && Array.isArray(claim.citations) && claim.citations.length > 0 && claim.citations.every(c => typeof c.quote === 'string' && c.quote.length >= 8 && value.sources.some(s => s.ref === c.ref && s.content.includes(c.quote))));
}

export function chatEvidenceLabel(mode) {
  return mode === 'KNOWLEDGE' ? '依据知识库' : mode === 'KNOWLEDGE_UNAVAILABLE' ? '资料未能确认' : '模型生成';
}
