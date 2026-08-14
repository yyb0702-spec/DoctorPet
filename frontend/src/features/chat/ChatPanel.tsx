import { useState } from 'react'
import { MessageCircle, Send } from 'lucide-react'
import { useMe } from '@/features/members/hooks'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { cn } from '@/lib/utils'
import { MemberRole, ReservationStatus } from '@/types/enums'
import { CHAT_MAX_LENGTH, isValidChatContent } from './messageUtils'
import { useReservationChat } from './useReservationChat'
import type { ChatConnectionState, ChatMessage } from './types'

const WRITABLE_STATUSES: ReservationStatus[] = [
  ReservationStatus.REQUESTED,
  ReservationStatus.CONFIRMED,
  ReservationStatus.NO_SHOW_PENDING,
  ReservationStatus.CHECKED_IN,
  ReservationStatus.IN_TREATMENT,
]

function fmt(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR')
}

function connectionLabel(state: ChatConnectionState): string {
  switch (state) {
    case 'connected':
      return '연결됨'
    case 'connecting':
      return '연결 중…'
    case 'reconnecting':
      return '재연결 중…'
    case 'mock':
      return '목 모드'
    case 'forbidden':
      return '채팅을 사용할 수 없어요'
    case 'failed':
      return '연결에 실패했어요'
    default:
      return '불러오는 중…'
  }
}

function MessageItem({ message, ownSender }: { message: ChatMessage; ownSender: string }) {
  const mine = message.senderType === ownSender
  return (
    <li className={cn('flex', mine ? 'justify-end' : 'justify-start')}>
      <div
        className={cn(
          'max-w-[85%] rounded-2xl px-3 py-2 text-sm',
          mine ? 'bg-primary text-primary-foreground' : 'bg-muted',
        )}
      >
        <p className="mb-1 text-xs opacity-75">{message.senderName}</p>
        <p className="whitespace-pre-wrap break-words">{message.content}</p>
        <time className="mt-1 block text-right text-[11px] opacity-70">
          {fmt(message.createdAt)}
        </time>
      </div>
    </li>
  )
}

export function ChatPanel({
  reservationId,
  reservationStatus,
}: {
  reservationId: number
  reservationStatus: ReservationStatus
}) {
  const [content, setContent] = useState('')
  const [sendError, setSendError] = useState(false)
  const { data: me, isLoading: isMeLoading } = useMe()
  const { messages, historyState, connectionState, sendState, sendMessage } =
    useReservationChat(reservationId)
  const writable = WRITABLE_STATUSES.includes(reservationStatus)
  const roleResolved =
    !isMeLoading &&
    (me?.role === MemberRole.GUARDIAN || me?.role === MemberRole.HOSPITAL_STAFF)
  const canSend =
    roleResolved && writable && connectionState === 'connected' && isValidChatContent(content)
  const canEdit = roleResolved && writable && connectionState === 'connected'
  const ownSender =
    me?.role === MemberRole.HOSPITAL_STAFF ? 'HOSPITAL' : 'GUARDIAN'

  const submit = async () => {
    if (!canSend || sendState === 'sending') return
    setSendError(false)
    const sent = await sendMessage(content, ownSender)
    if (sent) {
      setContent('')
      return
    }
    setSendError(true)
  }

  return (
    <Card>
      <CardHeader className="pb-3">
        <CardTitle className="flex items-center gap-2 text-lg">
          <MessageCircle className="h-5 w-5" />
          예약 문의
        </CardTitle>
        <p className="text-xs text-muted-foreground">
          {connectionLabel(connectionState)}
          {connectionState === 'mock' &&
            ' · 실시간 채팅은 서버에 연결한 환경에서 사용할 수 있어요.'}
        </p>
      </CardHeader>
      <CardContent className="space-y-3">
        {historyState === 'loading' && (
          <p className="py-8 text-center text-sm text-muted-foreground">
            대화 내용을 불러오는 중이에요.
          </p>
        )}
        {historyState === 'error' && (
          <p className="rounded-md bg-destructive/10 p-3 text-sm text-destructive">
            대화 내용을 불러오지 못했어요. 잠시 후 페이지를 새로고침해 주세요.
          </p>
        )}
        {historyState === 'ready' && messages.length === 0 && (
          <p className="py-8 text-center text-sm text-muted-foreground">
            아직 주고받은 메시지가 없어요.
          </p>
        )}
        {messages.length > 0 && (
          <ul className="max-h-96 space-y-2 overflow-y-auto pr-1" aria-label="채팅 메시지">
            {messages.map((message) => (
              <MessageItem
                key={message.messageId}
                message={message}
                ownSender={ownSender}
              />
            ))}
          </ul>
        )}

        {!writable && (
          <p className="rounded-md bg-muted p-3 text-sm text-muted-foreground">
            종료된 예약이라 이전 대화만 확인할 수 있어요.
          </p>
        )}
        {connectionState === 'forbidden' && (
          <p className="rounded-md bg-destructive/10 p-3 text-sm text-destructive">
            채팅 권한을 확인할 수 없어요. 예약 목록으로 돌아간 뒤 다시 시도해 주세요.
          </p>
        )}
        {(connectionState === 'failed' || connectionState === 'reconnecting') && (
          <p className="text-xs text-muted-foreground">
            연결이 안정되지 않았어요. 잠시 후 다시 시도하거나 페이지를 새로고침해 주세요.
          </p>
        )}
        {sendError && (
          <p className="rounded-md bg-destructive/10 p-3 text-sm text-destructive">
            전송을 확인하지 못했습니다. 입력한 내용은 유지되며, 연결을 확인한 뒤 다시 보낼 수 있습니다.
          </p>
        )}
        {!roleResolved && writable && (
          <p className="text-xs text-muted-foreground">사용자 권한을 확인하는 중이에요.</p>
        )}

        <div className="flex gap-2">
          <textarea
            aria-label="메시지 입력"
            className="min-h-20 flex-1 rounded-md border bg-background p-2 text-sm disabled:cursor-not-allowed disabled:opacity-60"
            value={content}
            maxLength={CHAT_MAX_LENGTH}
            disabled={!canEdit || sendState === 'sending'}
            onChange={(event) => {
              setContent(event.target.value)
              setSendError(false)
            }}
            placeholder={
              writable
                ? '메시지를 입력하세요'
                : '이 예약에서는 새 메시지를 보낼 수 없어요'
            }
          />
          <Button
            className="self-end"
            size="icon"
            disabled={!canSend || sendState === 'sending'}
            aria-label="메시지 전송"
            onClick={submit}
          >
            {sendState === 'sending' ? '…' : <Send className="h-4 w-4" />}
          </Button>
        </div>
        <p className="text-right text-xs text-muted-foreground">
          {content.trim().length}/{CHAT_MAX_LENGTH}
        </p>
      </CardContent>
    </Card>
  )
}
