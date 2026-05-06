export type Role = "user" | "assistant";

export type FeedbackValue = "like" | "dislike" | null;

export type MessageStatus = "streaming" | "done" | "cancelled" | "error";

export interface User {
  userId: string;
  username: string;
  avatar?: string;
  deptId: string | null;
  deptName: string | null;
  roleTypes: string[];
  maxSecurityLevel: number;
  isSuperAdmin: boolean;
  isDeptAdmin: boolean;
  /** Token is only present when stored from login response */
  token?: string;
}

export interface Session {
  id: string;
  title: string;
  lastTime?: string;
  kbId?: string;
}

export interface Message {
  id: string;
  role: Role;
  content: string;
  thinking?: string;
  thinkingDuration?: number;
  isDeepThinking?: boolean;
  isThinking?: boolean;
  createdAt?: string;
  feedback?: FeedbackValue;
  status?: MessageStatus;
  suggestedQuestions?: string[];
  sources?: SourceCard[];
}

export interface StreamMetaPayload {
  conversationId: string;
  taskId: string;
}

export interface MessageDeltaPayload {
  type: string;
  delta: string;
}

export interface CompletionPayload {
  messageId?: string | null;
  title?: string | null;
}

export interface SuggestionsPayload {
  messageId: string;
  questions: string[];
}

export interface SourceChunk {
  chunkId: string;
  chunkIndex: number;
  preview: string;
  score: number;
  // PR 6 chunk-level evidence。后端 @JsonInclude(NON_EMPTY) 让 null 字段在 wire 上不出现 →
  // 前端字段为 undefined；但 cache 漂移 / 历史数据仍可能产生 null，所以类型用 ?: T | null
  // 同时覆盖 undefined 和 null。守卫一律用 != null（不是 !== undefined）。
  pageNumber?: number | null;
  pageStart?: number | null;
  pageEnd?: number | null;
  headingPath?: string[] | null;
  blockType?: string | null;
  sourceBlockIds?: string[] | null;
}

export interface SourceCard {
  /** 引用编号，1..N（对应未来 LLM 输出的 [^n]）。 */
  index: number;
  docId: string;
  docName: string;
  kbId: string;
  topScore: number;
  chunks: SourceChunk[];
}

export interface SourcesPayload {
  conversationId: string;
  /** 流式阶段为 null；前端按 streamingMessageId 定位消息，不依赖此字段。 */
  messageId: string | null;
  cards: SourceCard[];
}
