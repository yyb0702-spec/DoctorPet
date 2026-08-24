import { Client } from '@stomp/stompjs'
import { tokenStore } from '@/lib/auth/tokenStore'
import type { ChatMessage } from './types'

const WS_PATH = '/ws/chat'

function websocketUrl(): string {
  const configured = import.meta.env.VITE_CHAT_WS_URL
  if (configured) return configured

  // Vite 개발 서버에서는 상대 URL이 프록시를 타고, 운영에서는 현재 origin으로 연결된다.
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${protocol}//${window.location.host}${WS_PATH}`
}

export interface ChatStompCallbacks {
  onConnected: (subscribe: () => void) => void
  onMessage: (message: ChatMessage) => void
  onAcknowledged: (clientMessageId: string) => void
  onSubscriptionReady: () => void
  onClosed: () => void
  onFailed: () => void
}

export function createChatStompClient(
  reservationId: number,
  callbacks: ChatStompCallbacks,
): Client | null {
  const accessToken = tokenStore.getAccessToken()
  if (!accessToken) return null

  const client = new Client({
    brokerURL: websocketUrl(),
    connectHeaders: { Authorization: `Bearer ${accessToken}` },
    reconnectDelay: 0,
    heartbeatIncoming: 10_000,
    heartbeatOutgoing: 10_000,
    debug: () => undefined,
  })

  client.onConnect = () => {
    client.subscribe('/user/queue/chat/send-acks', (frame) => {
      try {
        const ack = JSON.parse(frame.body) as { clientMessageId?: string }
        if (ack.clientMessageId) callbacks.onAcknowledged(ack.clientMessageId)
      } catch {
        // 잘못된 ACK 프레임은 채팅 수신을 중단시키지 않는다.
      }
    })
    callbacks.onConnected(() => {
      client.subscribe(`/topic/chat/reservations/${reservationId}`, (frame) => {
        try {
          const payload = JSON.parse(frame.body) as ChatMessage | { type?: string }
          if ('type' in payload && payload.type === 'SUBSCRIPTION_READY') {
            callbacks.onSubscriptionReady()
            return
          }
          callbacks.onMessage(payload as ChatMessage)
        } catch {
          // 단일 깨진 프레임이 이후 실시간 수신을 막지 않게 한다.
        }
      })
      client.publish({
        destination: `/app/chat/reservations/${reservationId}/subscription-ready`,
        body: '',
      })
    })
  }
  // STOMP ERROR는 토큰·권한·종료 상태 등 상세 사유를 노출하지 않고 공통 안내로 처리한다.
  client.onStompError = () => callbacks.onFailed()
  client.onWebSocketClose = () => callbacks.onClosed()

  return client
}
