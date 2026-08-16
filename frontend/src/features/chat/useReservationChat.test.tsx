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

    await waitFor(() => expect(getMessages).toHaveBeenLastCalledWith(11, 1))
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

  it('최초 이력 조회와 STOMP 구독 사이에 생긴 메시지를 커서 복구한다', async () => {
    const recovered = { ...message, messageId: 2, content: '구독 직전 메시지' }
    getMessages
      .mockResolvedValueOnce({
        messages: [message],
        nextAfterMessageId: null,
        hasNext: false,
      })
      .mockResolvedValueOnce({
        messages: [recovered],
        nextAfterMessageId: null,
        hasNext: false,
      })

    const { result } = renderHook(() => useReservationChat(11))

    await waitFor(() => expect(createChatStompClient).toHaveBeenCalled())
    act(() => {
      callbacks?.onConnected(() => undefined)
    })

    await waitFor(() => expect(getMessages).toHaveBeenLastCalledWith(11, 1))
    await waitFor(() => {
      expect(result.current.messages.map((item) => item.messageId)).toEqual([1, 2])
    })
  })

  it('SUBSCRIPTION_READY 후 최종 복구 실패를 처리하고 다음 재연결에 맡긴다', async () => {
    getMessages
      .mockResolvedValueOnce({
        messages: [message],
        nextAfterMessageId: null,
        hasNext: false,
      })
      .mockRejectedValueOnce(new Error('initial gap recovery failed'))
      .mockRejectedValueOnce(new Error('final gap recovery failed'))

    const { result } = renderHook(() => useReservationChat(11))
    await waitFor(() => expect(createChatStompClient).toHaveBeenCalled())
    act(() => {
      callbacks?.onConnected(() => undefined)
    })
    await waitFor(() => expect(getMessages).toHaveBeenCalledTimes(2))

    act(() => {
      callbacks?.onSubscriptionReady()
    })
    await waitFor(() => expect(getMessages).toHaveBeenCalledTimes(3))
    expect(result.current.connectionState).toBe('connected')
  })

  it('100건 초과 이력은 다음 커서까지 모두 복구한 뒤 읽음 처리한다', async () => {
    const firstPage = Array.from({ length: 100 }, (_, index) => ({
      ...message,
      messageId: index + 1,
      content: `메시지 ${index + 1}`,
    }))
    const latest = { ...message, messageId: 101, content: '최신 메시지' }
    getMessages
      .mockResolvedValueOnce({
        messages: firstPage,
        nextAfterMessageId: 100,
        hasNext: true,
      })
      .mockResolvedValueOnce({
        messages: [latest],
        nextAfterMessageId: null,
        hasNext: false,
      })
      .mockResolvedValueOnce({
        messages: [],
        nextAfterMessageId: null,
        hasNext: false,
      })

    const { result } = renderHook(() => useReservationChat(11))

    await waitFor(() => expect(createChatStompClient).toHaveBeenCalled())
    expect(getMessages.mock.calls).toEqual([
      [11, undefined],
      [11, 100],
    ])
    expect(markRead).not.toHaveBeenCalled()

    act(() => {
      callbacks?.onConnected(() => undefined)
    })
    await waitFor(() => expect(getMessages).toHaveBeenLastCalledWith(11, 101))

    // 구독이 실제로 활성화되기 전에는 읽음을 보내지 않는다.
    expect(markRead).not.toHaveBeenCalled()

    act(() => {
      callbacks?.onSubscriptionReady()
    })
    await waitFor(() => expect(markRead).toHaveBeenCalledWith(11, expect.any(Number)))

    expect(result.current.messages).toHaveLength(101)
    expect(result.current.messages.at(-1)?.content).toBe('최신 메시지')
  })

  it('SUBSCRIPTION_READY 전에는 읽음 처리하지 않고, 최종 복구로 누락분을 병합한 뒤에만 읽음 처리한다', async () => {
    const gapMessage = { ...message, messageId: 2, content: '구독 직전에 저장된 메시지' }
    getMessages
      // 최초 이력
      .mockResolvedValueOnce({ messages: [message], nextAfterMessageId: null, hasNext: false })
      // onConnected 예비 복구 — 아직 누락분이 보이지 않는다
      .mockResolvedValueOnce({ messages: [], nextAfterMessageId: null, hasNext: false })
      // SUBSCRIPTION_READY 최종 복구 — 구독 직전에 저장된 메시지를 여기서 받는다
      .mockResolvedValueOnce({ messages: [gapMessage], nextAfterMessageId: null, hasNext: false })

    const { result } = renderHook(() => useReservationChat(11))
    await waitFor(() => expect(createChatStompClient).toHaveBeenCalled())

    act(() => {
      callbacks?.onConnected(() => undefined)
    })
    // 예비 복구가 끝나도 준비 상태가 아니므로 읽음을 보내면 안 된다 — 보내면 아직 화면에 없는
    // gapMessage까지 읽음이 된다.
    await waitFor(() => expect(getMessages).toHaveBeenCalledTimes(2))
    await new Promise((resolve) => setTimeout(resolve, 600))
    expect(markRead).not.toHaveBeenCalled()

    act(() => {
      callbacks?.onSubscriptionReady()
    })
    await waitFor(() => expect(result.current.messages).toHaveLength(2))
    await waitFor(() => expect(markRead).toHaveBeenCalledWith(11, expect.any(Number)))
    expect(result.current.messages.at(-1)?.content).toBe('구독 직전에 저장된 메시지')
  })

  it('읽음 처리는 병합한 마지막 메시지 ID까지만 요청한다', async () => {
    // 최종 복구까지 messageId 2번을 병합한 상태. 그 뒤 상대가 보낸 3번이 DB에 저장됐지만
    // STOMP 도착이 지연되면, 읽음 요청 상한이 2여야 3번이 읽음 처리되지 않는다.
    const merged = { ...message, messageId: 2, content: '복구로 병합된 메시지' }
    getMessages
      .mockResolvedValueOnce({ messages: [message], nextAfterMessageId: null, hasNext: false })
      .mockResolvedValueOnce({ messages: [], nextAfterMessageId: null, hasNext: false })
      .mockResolvedValueOnce({ messages: [merged], nextAfterMessageId: null, hasNext: false })

    renderHook(() => useReservationChat(11))
    await waitFor(() => expect(createChatStompClient).toHaveBeenCalled())

    act(() => {
      callbacks?.onConnected(() => undefined)
    })
    act(() => {
      callbacks?.onSubscriptionReady()
    })

    await waitFor(() => expect(markRead).toHaveBeenCalledWith(11, 2))
  })

  it('서버가 방송한 자신의 메시지를 받은 뒤에만 전송 성공으로 처리한다', async () => {
    const { result } = renderHook(() => useReservationChat(11))

    await waitFor(() => expect(createChatStompClient).toHaveBeenCalled())
    act(() => {
      callbacks?.onConnected(() => undefined)
    })

    let sending: Promise<boolean> | undefined
    act(() => {
      sending = result.current.sendMessage('전송 확인 메시지', 'GUARDIAN')
    })
    expect(result.current.sendState).toBe('sending')
    const published = client.publish.mock.calls[0]?.[0]
    expect(JSON.parse(published.body)).toMatchObject({
      content: '전송 확인 메시지',
      clientMessageId: expect.any(String),
    })

    act(() => {
      callbacks?.onAcknowledged(JSON.parse(published.body).clientMessageId)
    })
    await expect(sending).resolves.toBe(true)
    await waitFor(() => expect(result.current.sendState).toBe('idle'))
  })

  it('전송 ACK와 echo가 유실돼도 REST 복구의 clientMessageId로 전송 성공을 확정한다', async () => {
    const sentMessage = { ...message, messageId: 2, content: 'REST 재확인 메시지' }
    getMessages
      .mockResolvedValueOnce({
        messages: [message],
        nextAfterMessageId: null,
        hasNext: false,
      })
      .mockResolvedValueOnce({
        messages: [],
        nextAfterMessageId: null,
        hasNext: false,
      })
      .mockImplementationOnce(() => {
        const published = client.publish.mock.calls[0]?.[0]
        return Promise.resolve({
          messages: [{ ...sentMessage, clientMessageId: JSON.parse(published.body).clientMessageId }],
          nextAfterMessageId: null,
          hasNext: false,
        })
      })

    const { result } = renderHook(() => useReservationChat(11))
    await waitFor(() => expect(createChatStompClient).toHaveBeenCalled())
    act(() => {
      callbacks?.onConnected(() => undefined)
    })
    await waitFor(() => expect(getMessages).toHaveBeenLastCalledWith(11, 1))

    let sending: Promise<boolean> | undefined
    act(() => {
      sending = result.current.sendMessage('REST 재확인 메시지', 'GUARDIAN')
    })

    await act(async () => {
      await expect(sending).resolves.toBe(true)
    })
    expect(getMessages).toHaveBeenCalledTimes(3)
    expect(getMessages).toHaveBeenLastCalledWith(11, 1)
    await waitFor(() => {
      expect(result.current.messages.map((item) => item.messageId)).toEqual([1, 2])
    })
  }, 10_000)

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
