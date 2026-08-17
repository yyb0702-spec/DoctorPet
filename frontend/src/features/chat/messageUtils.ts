import type { ChatMessage } from './types'

export const CHAT_MAX_LENGTH = 1_000

// REST 복구와 STOMP가 같은 메시지를 전달해도 ID 단위로 하나만 화면에 남긴다.
export function mergeChatMessages(
  current: ChatMessage[],
  incoming: ChatMessage[],
): ChatMessage[] {
  const byId = new Map(current.map((message) => [message.messageId, message]))
  incoming.forEach((message) => byId.set(message.messageId, message))
  return [...byId.values()].sort(
    (a, b) =>
      new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime() ||
      a.messageId - b.messageId,
  )
}

export function normalizeChatContent(content: string): string {
  return content.trim()
}

export function isValidChatContent(content: string): boolean {
  const normalized = normalizeChatContent(content)
  return normalized.length >= 1 && normalized.length <= CHAT_MAX_LENGTH
}
