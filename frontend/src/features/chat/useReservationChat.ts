import { useCallback, useEffect, useRef, useState } from 'react'
import type { Client } from '@stomp/stompjs'
import { tokenStore } from '@/lib/auth/tokenStore'
import { chatApi } from './api'
import { isValidChatContent, mergeChatMessages, normalizeChatContent } from './messageUtils'
import { createChatStompClient } from './stompClient'
import type { ChatConnectionState, ChatMessage } from './types'

const RECONNECT_BASE_MS = 1_000
const RECONNECT_MAX_MS = 30_000
const READ_DEBOUNCE_MS = 500

function isMockMode(): boolean {
  return import.meta.env.VITE_ENABLE_MOCKS === 'true'
}

export function useReservationChat(reservationId: number, enabled = true) {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [historyState, setHistoryState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [connectionState, setConnectionState] =
    useState<ChatConnectionState>('loading')
  const clientRef = useRef<Client | null>(null)
  const lastMessageIdRef = useRef<number | undefined>(undefined)
  const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const readTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const retryAttemptRef = useRef(0)
  const stoppedRef = useRef(false)
  const intentionalDisconnectRef = useRef(false)

  const appendMessages = useCallback((incoming: ChatMessage[]) => {
    if (incoming.length === 0) return
    setMessages((current) => {
      const merged = mergeChatMessages(current, incoming)
      lastMessageIdRef.current = merged.at(-1)?.messageId
      return merged
    })
  }, [])

  const markReadDebounced = useCallback(() => {
    if (readTimerRef.current) clearTimeout(readTimerRef.current)
    readTimerRef.current = setTimeout(() => {
      chatApi.markRead(reservationId).catch(() => {
        // 읽음 동기화 실패는 메시지 조회·수신을 막지 않는다.
      })
    }, READ_DEBOUNCE_MS)
  }, [reservationId])

  useEffect(() => {
    if (!enabled || !Number.isFinite(reservationId)) return

    stoppedRef.current = false
    intentionalDisconnectRef.current = false
    retryAttemptRef.current = 0
    lastMessageIdRef.current = undefined
    // 예약 상세 간 이동 시 이전 스레드가 잠깐 보이지 않게 다음 이벤트 루프에서 초기화한다.
    // React effect 본문에서 동기 setState를 피하면서도 첫 REST 요청보다 먼저 반영된다.
    let activeClient: Client | null = null

    const recover = async (afterMessageId?: number) => {
      const page = await chatApi.getMessages(reservationId, afterMessageId)
      appendMessages(page.messages)
      if (page.messages.length > 0) markReadDebounced()
      return page
    }

    const stopClient = async () => {
      if (!activeClient) return
      intentionalDisconnectRef.current = true
      const closing = activeClient
      activeClient = null
      clientRef.current = null
      await closing.deactivate()
    }

    const scheduleReconnect = () => {
      if (stoppedRef.current || retryTimerRef.current) return
      setConnectionState('reconnecting')
      const delay = Math.min(
        RECONNECT_BASE_MS * 2 ** retryAttemptRef.current,
        RECONNECT_MAX_MS,
      )
      retryAttemptRef.current += 1
      retryTimerRef.current = setTimeout(() => {
        retryTimerRef.current = null
        void connect(true)
      }, delay)
    }

    const connect = async (reconnecting: boolean) => {
      if (stoppedRef.current || isMockMode()) return
      if (reconnecting && lastMessageIdRef.current != null) {
        try {
          await recover(lastMessageIdRef.current)
        } catch {
          // 소켓은 다시 열어 두고, 연결 직후 한 번 더 누락분을 복구한다.
        }
      }
      if (stoppedRef.current) return

      setConnectionState(reconnecting ? 'reconnecting' : 'connecting')
      intentionalDisconnectRef.current = false
      const client = createChatStompClient(reservationId, {
        onConnected: (subscribe) => {
          if (stoppedRef.current || client !== activeClient) return
          subscribe()
          retryAttemptRef.current = 0
          setConnectionState('connected')
          // CONNECT 직후에도 다시 복구한다. REST·STOMP 동시 도착은 messageId로 병합된다.
          if (reconnecting && lastMessageIdRef.current != null) {
            void recover(lastMessageIdRef.current).catch(() => undefined)
          }
        },
        onMessage: (message) => {
          appendMessages([message])
          markReadDebounced()
        },
        onClosed: () => {
          if (
            client === activeClient &&
            !stoppedRef.current &&
            !intentionalDisconnectRef.current
          ) {
            scheduleReconnect()
          }
        },
        onFailed: () => {
          if (stoppedRef.current) return
          setConnectionState('forbidden')
          void stopClient()
        },
      })
      if (!client) {
        setConnectionState('failed')
        return
      }
      activeClient = client
      clientRef.current = client
      client.activate()
    }

    const loadInitial = async () => {
      try {
        await recover()
        if (stoppedRef.current) return
        setHistoryState('ready')
        markReadDebounced()
        if (isMockMode()) return
        await connect(false)
      } catch {
        if (!stoppedRef.current) {
          setHistoryState('error')
          setConnectionState('failed')
        }
      }
    }

    const resetTimer = setTimeout(() => {
      if (stoppedRef.current) return
      setMessages([])
      setHistoryState('loading')
      setConnectionState(isMockMode() ? 'mock' : 'connecting')
      void loadInitial()
    }, 0)

    const unsubscribeToken = tokenStore.subscribe(() => {
      if (stoppedRef.current || isMockMode() || !activeClient) return
      if (!tokenStore.getAccessToken()) {
        // 로그아웃·세션 만료에서는 재연결하지 않고 즉시 구독과 소켓을 정리한다.
        setConnectionState('failed')
        void stopClient()
        return
      }
      // Axios 재발급으로 토큰 쌍이 바뀌면 이전 CONNECT를 정리하고 최신 토큰으로 다시 연결한다.
      void stopClient().then(() => connect(true))
    })

    return () => {
      stoppedRef.current = true
      unsubscribeToken()
      clearTimeout(resetTimer)
      if (retryTimerRef.current) clearTimeout(retryTimerRef.current)
      if (readTimerRef.current) clearTimeout(readTimerRef.current)
      void stopClient()
    }
  }, [appendMessages, enabled, markReadDebounced, reservationId])

  const sendMessage = useCallback(
    (content: string): boolean => {
      const normalized = normalizeChatContent(content)
      if (!isValidChatContent(normalized) || !clientRef.current?.connected) {
        return false
      }
      clientRef.current.publish({
        destination: `/app/chat/reservations/${reservationId}/messages`,
        body: JSON.stringify({ content: normalized }),
      })
      return true
    },
    [reservationId],
  )

  return {
    messages,
    historyState,
    connectionState,
    sendMessage,
  }
}
