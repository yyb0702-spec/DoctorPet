import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { tokenStore } from '@/lib/auth/tokenStore'
import type { ChatStompCallbacks } from './stompClient'
import { useReservationChat } from './useReservationChat'

const getMessages = vi.hoisted(() => vi.fn())
const markRead = vi.hoisted(() => vi.fn())
const createChatStompClient = vi.hoisted(() => vi.fn())

vi.mock('./api', () => ({ chatApi: { getMessages, markRead } }))
vi.mock('./stompClient', () => ({ createChatStompClient }))

const message = {
  messageId: 1,
  senderType: 'GUARDIAN' as const,
  content: '안녕하세요',
  createdAt: '2026-08-13T10:00:00',
  senderName: '보호자',
}

describe('useReservationChat', () => {
  let callbacks: ChatStompCallbacks | undefined
  const client = {
    connected: true,
    activate: vi.fn(),
    deactivate: vi.fn().mockResolvedValue(undefined),
    publish: vi.fn(),
  }

  beforeEach(() => {
    vi.useRealTimers()
    vi.clearAllMocks()
    callbacks = undefined
    getMessages.mockResolvedValue({
      messages: [message],
      nextAfterMessageId: null,
      hasNext: false,
    })
    markRead.mockResolvedValue(undefined)
    createChatStompClient.mockImplementation(
      (_reservationId: number, received: ChatStompCallbacks) => {
        callbacks = received
        return client
      },
    )
  })

  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it('초기 REST 이력 뒤에 연결하고, STOMP 수신을 병합한다', async () => {
    const { result, unmount } = renderHook(() => useReservationChat(11))

    await waitFor(() => expect(createChatStompClient).toHaveBeenCalled())
    act(() => {
      callbacks?.onConnected(() => undefined)
    })

    expect(result.current.messages).toHaveLength(1)
    act(() => {
      // onMessage는 STOMP frame JSON 파싱 뒤 service가 호출한다.
      callbacks?.onMessage({ ...message, messageId: 2, content: '병원입니다.' })
      callbacks?.onMessage(message)
    })
    expect(result.current.messages.map((item) => item.messageId)).toEqual([1, 2])
    unmount()
    await waitFor(() => expect(client.deactivate).toHaveBeenCalled())
  })

  it('초기 이력 조회 실패 시 연결을 시작하지 않는다', async () => {
    getMessages.mockRejectedValueOnce(new Error('network'))
    const { result } = renderHook(() => useReservationChat(11))

    await waitFor(() => expect(result.current.historyState).toBe('error'))
    expect(createChatStompClient).not.toHaveBeenCalled()
  })

  it('mock 모드에서는 STOMP 연결을 시도하지 않는다', async () => {
    vi.stubEnv('VITE_ENABLE_MOCKS', 'true')
    const { result } = renderHook(() => useReservationChat(11))

    await waitFor(() => expect(result.current.historyState).toBe('ready'))
    expect(result.current.connectionState).toBe('mock')
    expect(createChatStompClient).not.toHaveBeenCalled()
  })

  it('로그아웃·세션 만료 시 기존 STOMP 연결을 즉시 정리한다', async () => {
    renderHook(() => useReservationChat(11))
    await waitFor(() => expect(createChatStompClient).toHaveBeenCalled())

    act(() => tokenStore.clear())
    await waitFor(() => expect(client.deactivate).toHaveBeenCalled())
    expect(createChatStompClient).toHaveBeenCalledTimes(1)
  })
})
