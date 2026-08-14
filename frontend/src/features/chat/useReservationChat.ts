import { useCallback, useEffect, useRef, useState } from 'react'
import type { Client } from '@stomp/stompjs'
import { tokenStore } from '@/lib/auth/tokenStore'
import { chatApi } from './api'
import { isValidChatContent, mergeChatMessages, normalizeChatContent } from './messageUtils'
import { createChatStompClient } from './stompClient'
import type { ChatConnectionState, ChatMessage, ChatSenderType } from './types'

const RECONNECT_BASE_MS = 1_000
const RECONNECT_MAX_MS = 30_000
const READ_DEBOUNCE_MS = 500
const SEND_CONFIRM_TIMEOUT_MS = 5_000

type PendingSend = {
  clientMessageId: string
  content: string
  senderType: ChatSenderType
  minMessageId?: number
  timer: ReturnType<typeof setTimeout>
  resolve: (sent: boolean) => void
}

function isMockMode(): boolean {
  return import.meta.env.VITE_ENABLE_MOCKS === 'true'
}

export function useReservationChat(reservationId: number, enabled = true) {
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [historyState, setHistoryState] = useState<'loading' | 'ready' | 'error'>('loading')
  const [connectionState, setConnectionState] =
    useState<ChatConnectionState>('loading')
  const [sendState, setSendState] = useState<'idle' | 'sending' | 'failed'>('idle')
  const clientRef = useRef<Client | null>(null)
  const messagesRef = useRef<ChatMessage[]>([])
  const lastMessageIdRef = useRef<number | undefined>(undefined)
  const retryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const readTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const retryAttemptRef = useRef(0)
  const stoppedRef = useRef(false)
  const intentionalDisconnectRef = useRef(false)
  const subscriptionReadyRef = useRef(false)
  const historySyncedRef = useRef(false)
  const pendingSendRef = useRef<PendingSend | null>(null)
  const retryClientMessageRef = useRef<{ content: string; clientMessageId: string } | null>(null)
  const recoverRef = useRef<((afterMessageId?: number) => Promise<void>) | null>(null)

  const markReadDebounced = useCallback(() => {
    if (readTimerRef.current) clearTimeout(readTimerRef.current)
    readTimerRef.current = setTimeout(() => {
      if (!subscriptionReadyRef.current || !historySyncedRef.current) return
      chatApi.markRead(reservationId).catch(() => {
        // 읽음 동기화 실패는 메시지 조회·수신을 막지 않는다.
      })
    }, READ_DEBOUNCE_MS)
  }, [reservationId])

  const settlePendingSend = useCallback((sent: boolean) => {
    const pending = pendingSendRef.current
    if (!pending) return

    clearTimeout(pending.timer)
    pendingSendRef.current = null
    retryClientMessageRef.current = sent
      ? null
      : { content: pending.content, clientMessageId: pending.clientMessageId }
    setSendState(sent ? 'idle' : 'failed')
    pending.resolve(sent)
  }, [])

  const failPendingSend = useCallback(() => {
    settlePendingSend(false)
  }, [settlePendingSend])

  const appendMessages = useCallback((incoming: ChatMessage[]) => {
    if (incoming.length === 0) return
    const merged = mergeChatMessages(messagesRef.current, incoming)
    messagesRef.current = merged
    lastMessageIdRef.current = merged.at(-1)?.messageId
    setMessages(merged)

    const pending = pendingSendRef.current
    if (pending && incoming.some((message) => message.clientMessageId === pending.clientMessageId)) {
      settlePendingSend(true)
    }

  }, [settlePendingSend])

  useEffect(() => {
    if (!enabled || !Number.isFinite(reservationId)) return

    stoppedRef.current = false
    intentionalDisconnectRef.current = false
    retryAttemptRef.current = 0
    messagesRef.current = []
    lastMessageIdRef.current = undefined
    subscriptionReadyRef.current = false
    historySyncedRef.current = false
    // 예약 상세 간 이동 시 이전 스레드가 잠깐 보이지 않게 다음 이벤트 루프에서 초기화한다.
    // React effect 본문에서 동기 setState를 피하면서도 첫 REST 요청보다 먼저 반영된다.
    let activeClient: Client | null = null

    const recover = async (afterMessageId?: number) => {
      let cursor = afterMessageId

      do {
        const page = await chatApi.getMessages(reservationId, cursor)
        appendMessages(page.messages)
        cursor = page.nextAfterMessageId ?? undefined

        if (!page.hasNext) return
      } while (cursor != null)

      throw new Error('채팅 이력 커서가 누락되었습니다.')
    }
    recoverRef.current = recover

    const stopClient = async () => {
      if (!activeClient) return
      intentionalDisconnectRef.current = true
      subscriptionReadyRef.current = false
      historySyncedRef.current = false
      failPendingSend()
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
          subscriptionReadyRef.current = true
          void recover(lastMessageIdRef.current)
            .then(() => {
              if (stoppedRef.current || client !== activeClient) return
              historySyncedRef.current = true
              markReadDebounced()
            })
            .catch(() => {
              // 누락 구간 복구 전에는 읽음 처리를 보류하고 다음 재연결에서 다시 복구한다.
            })
        },
        onMessage: (message) => {
          appendMessages([message])
          markReadDebounced()
        },
        onAcknowledged: (clientMessageId) => {
          if (pendingSendRef.current?.clientMessageId === clientMessageId) {
            settlePendingSend(true)
          }
        },
        onSubscriptionReady: () => {
          // SimpleBroker는 SUBSCRIBE receipt를 발급하지 않는다. topic으로 되돌아온 제어 프레임을
          // 실제 구독 활성화 증거로 삼아, 최초 조회와 구독 사이의 누락 구간을 최종 복구한다.
          void recover(lastMessageIdRef.current).catch(() => {
            // 최종 커서 복구 실패는 전송·수신 루프를 끊지 않고, 다음 재연결에서 다시 시도한다.
          })
        },
        onClosed: () => {
          subscriptionReadyRef.current = false
          historySyncedRef.current = false
          failPendingSend()
          if (readTimerRef.current) clearTimeout(readTimerRef.current)
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
      if (recoverRef.current === recover) recoverRef.current = null
      void stopClient()
    }
  }, [appendMessages, enabled, failPendingSend, markReadDebounced, reservationId])

  const confirmPendingSendAfterTimeout = useCallback(async () => {
    const pending = pendingSendRef.current
    const recover = recoverRef.current
    if (!pending || !recover) {
      settlePendingSend(false)
      return
    }

    try {
      await recover(pending.minMessageId)
    } catch {
      // 조회 실패 시에는 저장 여부를 확정할 수 없으므로 전송 실패로 안내한다.
    }

    if (pendingSendRef.current === pending) {
      settlePendingSend(false)
    }
  }, [settlePendingSend])

  const sendMessage = useCallback(
    (content: string, senderType: ChatSenderType): Promise<boolean> => {
      const normalized = normalizeChatContent(content)
      const client = clientRef.current
      if (
        !isValidChatContent(normalized) ||
        !client?.connected ||
        pendingSendRef.current
      ) {
        return Promise.resolve(false)
      }

      setSendState('sending')

      return new Promise((resolve) => {
        const timer = setTimeout(() => {
          void confirmPendingSendAfterTimeout()
        }, SEND_CONFIRM_TIMEOUT_MS)
        const retry = retryClientMessageRef.current
        const clientMessageId = retry?.content === normalized
          ? retry.clientMessageId
          : crypto.randomUUID()
        pendingSendRef.current = {
          clientMessageId,
          content: normalized,
          senderType,
          minMessageId: lastMessageIdRef.current,
          timer,
          resolve,
        }

        try {
          client.publish({
            destination: `/app/chat/reservations/${reservationId}/messages`,
            body: JSON.stringify({
              content: normalized,
              clientMessageId,
            }),
          })
        } catch {
          settlePendingSend(false)
        }
      })
    },
    [confirmPendingSendAfterTimeout, reservationId, settlePendingSend],
  )

  return {
    messages,
    historyState,
    connectionState,
    sendState,
    sendMessage,
  }
}
