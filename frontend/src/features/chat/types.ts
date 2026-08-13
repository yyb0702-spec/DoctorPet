export type ChatSenderType = 'GUARDIAN' | 'HOSPITAL'

export interface ChatMessage {
  messageId: number
  senderType: ChatSenderType
  content: string
  createdAt: string
  senderName: string
}

export interface ChatMessagePage {
  messages: ChatMessage[]
  nextAfterMessageId: number | null
  hasNext: boolean
}

export type ChatConnectionState =
  | 'loading'
  | 'connecting'
  | 'connected'
  | 'reconnecting'
  | 'failed'
  | 'forbidden'
  | 'mock'
