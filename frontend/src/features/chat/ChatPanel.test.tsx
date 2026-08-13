import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemberRole, ReservationStatus } from '@/types/enums'
import { ChatPanel } from './ChatPanel'
import { useReservationChat } from './useReservationChat'

vi.mock('@/features/members/hooks', () => ({
  useMe: () => ({ data: { role: MemberRole.GUARDIAN } }),
}))
vi.mock('./useReservationChat', () => ({ useReservationChat: vi.fn() }))

const mockedUseReservationChat = vi.mocked(useReservationChat)
const sendMessage = vi.fn(() => true)

describe('ChatPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockedUseReservationChat.mockReturnValue({
      messages: [],
      historyState: 'ready',
      connectionState: 'connected',
      sendMessage,
    })
  })

  it('빈 초기 이력 상태를 안내한다', () => {
    render(<ChatPanel reservationId={11} reservationStatus={ReservationStatus.CONFIRMED} />)

    expect(screen.getByText('아직 주고받은 메시지가 없어요.')).toBeInTheDocument()
  })

  it('이력 조회 실패를 안내한다', () => {
    mockedUseReservationChat.mockReturnValue({
      messages: [],
      historyState: 'error',
      connectionState: 'failed',
      sendMessage,
    })
    render(<ChatPanel reservationId={11} reservationStatus={ReservationStatus.CONFIRMED} />)

    expect(screen.getByText(/대화 내용을 불러오지 못했어요/)).toBeInTheDocument()
  })

  it('STOMP로 받은 메시지를 화면에 표시하고 전송한다', () => {
    mockedUseReservationChat.mockReturnValue({
      messages: [
        {
          messageId: 3,
          senderType: 'HOSPITAL',
          content: '진료 전에 금식 여부를 확인해 주세요.',
          createdAt: '2026-08-13T10:00:00',
          senderName: '행복동물병원',
        },
      ],
      historyState: 'ready',
      connectionState: 'connected',
      sendMessage,
    })
    render(<ChatPanel reservationId={11} reservationStatus={ReservationStatus.CONFIRMED} />)

    expect(screen.getByText('행복동물병원')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('메시지 입력'), {
      target: { value: '네, 확인하겠습니다.' },
    })
    fireEvent.click(screen.getByRole('button', { name: '메시지 전송' }))
    expect(sendMessage).toHaveBeenCalledWith('네, 확인하겠습니다.')
  })

  it('읽기 전용 예약과 공백·1,000자 초과 입력의 전송을 막는다', () => {
    const { rerender } = render(
      <ChatPanel reservationId={11} reservationStatus={ReservationStatus.CONFIRMED} />,
    )
    const input = screen.getByLabelText('메시지 입력')
    const submit = screen.getByRole('button', { name: '메시지 전송' })

    fireEvent.change(input, { target: { value: '   ' } })
    expect(submit).toBeDisabled()
    fireEvent.change(input, { target: { value: '가'.repeat(1_001) } })
    expect(submit).toBeDisabled()

    rerender(
      <ChatPanel
        reservationId={11}
        reservationStatus={ReservationStatus.TREATMENT_COMPLETED}
      />,
    )
    expect(screen.getByLabelText('메시지 입력')).toBeDisabled()
    expect(screen.getByText(/이전 대화만 확인/)).toBeInTheDocument()
  })
})
