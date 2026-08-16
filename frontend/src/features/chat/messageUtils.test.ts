import { describe, expect, it } from 'vitest'
import { CHAT_MAX_LENGTH, isValidChatContent, mergeChatMessages } from './messageUtils'

const first = {
  messageId: 1,
  senderType: 'GUARDIAN' as const,
  content: '안녕하세요',
  createdAt: '2026-08-13T10:00:00',
  senderName: '보호자',
}

describe('채팅 메시지 유틸', () => {
  it('REST 복구와 STOMP의 동일 messageId를 중복 표시하지 않는다', () => {
    const merged = mergeChatMessages(
      [first],
      [first, { ...first, messageId: 2, content: '병원입니다' }],
    )

    expect(merged).toHaveLength(2)
    expect(merged.map((message) => message.messageId)).toEqual([1, 2])
  })

  it('공백과 1,000자 초과 메시지를 막는다', () => {
    expect(isValidChatContent('   ')).toBe(false)
    expect(isValidChatContent('문의드립니다.')).toBe(true)
    expect(isValidChatContent('가'.repeat(CHAT_MAX_LENGTH + 1))).toBe(false)
  })
})
