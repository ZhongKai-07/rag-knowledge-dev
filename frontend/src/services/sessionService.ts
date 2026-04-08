import { api } from "@/services/api";

export interface ConversationVO {
  conversationId: string;
  title: string;
  lastTime?: string;
  kbId?: string;
}

export interface ConversationMessageVO {
  id: number | string;
  conversationId: string;
  role: string;
  content: string;
  vote: number | null;
  createTime?: string;
}

export async function listSessions(kbId?: string) {
  return api.get<ConversationVO[]>("/conversations", {
    params: kbId ? { kbId } : undefined
  });
}

export async function deleteSession(conversationId: string, kbId?: string) {
  return api.delete<void>(`/conversations/${conversationId}`, {
    params: kbId ? { kbId } : undefined
  });
}

export async function renameSession(conversationId: string, title: string, kbId?: string) {
  return api.put<void>(`/conversations/${conversationId}`, { title }, {
    params: kbId ? { kbId } : undefined
  });
}

export async function listMessages(conversationId: string) {
  return api.get<ConversationMessageVO[]>(`/conversations/${conversationId}/messages`);
}
