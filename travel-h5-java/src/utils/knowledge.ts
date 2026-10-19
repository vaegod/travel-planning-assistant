import { api } from "./http";
export interface KnowledgeDoc {
  id: string;
  title: string;
  city: string;
  sourceUrl: string;
  publishedOn: string | null;
  validFrom: string | null;
  validTo: string | null;
  origin: string;
  embeddingModel: string;
  dimensions: number;
  chunkCount: number;
  createdAt: string;
}
export interface KnowledgePiece {
  id: string;
  ordinal: number;
  startOffset: number;
  endOffset: number;
  content: string;
}
export interface KnowledgeDetail {
  document: KnowledgeDoc;
  content: string;
  chunks: KnowledgePiece[];
}
export interface KnowledgeImport {
  title: string;
  city: string;
  sourceUrl: string;
  publishedOn: string | null;
  validFrom: string | null;
  validTo: string | null;
  content: string;
}
export interface KnowledgeMatch {
  ref: string;
  chunkId: string;
  documentId: string;
  ordinal: number;
  title: string;
  city: string;
  sourceUrl: string;
  publishedOn: string | null;
  importedAt: string;
  origin: string;
  score: number;
  content: string;
}
export interface KnowledgeAnswer {
  abstained: boolean;
  reason: string | null;
  claims: { text: string; citations: { ref: string; quote: string }[] }[];
  sources: KnowledgeMatch[];
  notice: string;
}
export interface RagQuestion {
  id: string;
  question: string;
  city: string;
  asOf: string;
  status: "QUEUED" | "RUNNING" | "READY" | "ABSTAINED" | "ERROR" | "CANCELLED";
  answer: KnowledgeAnswer | null;
  errorCode: string | null;
  message: string | null;
  createdAt: string;
}
export interface ChatEvidence {
  mode: 'KNOWLEDGE' | 'KNOWLEDGE_UNAVAILABLE' | 'MODEL_ONLY' | 'MODEL_NO_MATCH' | 'MODEL_INSUFFICIENT' | 'MODEL_RETRIEVAL_ERROR';
  city: string;
  notice: string;
  sources: KnowledgeMatch[];
  claims: {text:string;citations:{ref:string;quote:string}[]}[];
}
export const knowledgeApi = {
  documents: () => api<KnowledgeDoc[]>("/knowledge/documents"),
  detail: (id: string) =>
    api<KnowledgeDetail>(`/knowledge/documents/${encodeURIComponent(id)}`),
  import: (value: KnowledgeImport) =>
    api<{ document: KnowledgeDoc; duplicate: boolean }>(
      "/knowledge/documents",
      "POST",
      value,
    ),
  seed: () =>
    api<{ imported: number; duplicates: number; failures: string[] }>(
      "/knowledge/seed",
      "POST",
      {},
    ),
  delete: (id: string) =>
    api<null>(`/knowledge/documents/${encodeURIComponent(id)}`, "DELETE"),
  search: (question: string, city: string, asOf: string) =>
    api<{ matches: KnowledgeMatch[]; durationMs: number }>(
      "/knowledge/search",
      "POST",
      { question, city, asOf: asOf || null, topK: 4 },
    ),
  ask: (question: string, city: string, asOf: string) =>
    api<RagQuestion>("/knowledge/questions", "POST", {
      question,
      city,
      asOf: asOf || null,
      topK: 4,
    }),
  answer: (id: string) =>
    api<RagQuestion>(`/knowledge/questions/${encodeURIComponent(id)}`),
  history: () => api<RagQuestion[]>("/knowledge/questions"),
  cancel: (id: string) =>
    api<{ cancelled: boolean }>(
      `/knowledge/questions/${encodeURIComponent(id)}/cancel`,
      "POST",
      {},
    ),
  deleteAnswer: (id: string) =>
    api<null>(`/knowledge/questions/${encodeURIComponent(id)}`, "DELETE"),
};
