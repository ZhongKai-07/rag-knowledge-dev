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
  /** 流式阶段提示（旁路），仅 streaming 期间有效，不进历史持久化。 */
  streamStatus?: StreamStatusPayload;
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

export type StreamStatusPhase =
  | "rewriting"
  | "retrieving"
  | "sources_ready"
  | "generating";

export interface StreamStatusPayload {
  phase: StreamStatusPhase;
  text: string;
  sourceCount?: number;
}
