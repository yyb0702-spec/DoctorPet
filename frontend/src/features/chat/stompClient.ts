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
    callbacks.onConnected(() => {
      client.subscribe(`/topic/chat/reservations/${reservationId}`, (frame) => {
        try {
          callbacks.onMessage(JSON.parse(frame.body) as ChatMessage)
        } catch {
          // 단일 깨진 프레임이 이후 실시간 수신을 막지 않게 한다.
        }
      })
    })
  }
  // STOMP ERROR는 토큰·권한·종료 상태 등 상세 사유를 노출하지 않고 공통 안내로 처리한다.
  client.onStompError = () => callbacks.onFailed()
  client.onWebSocketClose = () => callbacks.onClosed()

  return client
}
