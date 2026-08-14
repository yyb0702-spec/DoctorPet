import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemberRole, ReservationStatus } from '@/types/enums'
import { useMe } from '@/features/members/hooks'
import { ChatPanel } from './ChatPanel'
import { useReservationChat } from './useReservationChat'

vi.mock('@/features/members/hooks', () => ({ useMe: vi.fn() }))
vi.mock('./useReservationChat', () => ({ useReservationChat: vi.fn() }))

const mockedUseReservationChat = vi.mocked(useReservationChat)
const mockedUseMe = vi.mocked(useMe)
const sendMessage = vi.fn(async () => true)

describe('ChatPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockedUseMe.mockReturnValue({ data: { role: MemberRole.GUARDIAN }, isLoading: false } as ReturnType<typeof useMe>)
    mockedUseReservationChat.mockReturnValue({
      messages: [],
      historyState: 'ready',
      connectionState: 'connected',
      sendState: 'idle',
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
      sendState: 'idle',
      sendMessage,
    })
    render(<ChatPanel reservationId={11} reservationStatus={ReservationStatus.CONFIRMED} />)

    expect(screen.getByText(/대화 내용을 불러오지 못했어요/)).toBeInTheDocument()
  })

  it('STOMP로 받은 메시지를 화면에 표시하고 전송 확인 뒤 입력을 비운다', async () => {
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
      sendState: 'idle',
      sendMessage,
    })
    render(<ChatPanel reservationId={11} reservationStatus={ReservationStatus.CONFIRMED} />)

    expect(screen.getByText('행복동물병원')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('메시지 입력'), {
      target: { value: '네, 확인하겠습니다.' },
    })
    fireEvent.click(screen.getByRole('button', { name: '메시지 전송' }))
    expect(sendMessage).toHaveBeenCalledWith('네, 확인하겠습니다.', 'GUARDIAN')
    await waitFor(() => expect(screen.getByLabelText('메시지 입력')).toHaveValue(''))
  })

  it('전송 확인에 실패하면 입력을 유지하고 다시 전송할 수 있게 안내한다', async () => {
    sendMessage.mockResolvedValueOnce(false)
    render(<ChatPanel reservationId={11} reservationStatus={ReservationStatus.CONFIRMED} />)

    const input = screen.getByLabelText('메시지 입력')
    fireEvent.change(input, { target: { value: '전송 여부 확인' } })
    fireEvent.click(screen.getByRole('button', { name: '메시지 전송' }))

    await waitFor(() => {
      expect(screen.getByText(/전송을 확인하지 못했습니다/)).toBeInTheDocument()
    })
    expect(input).toHaveValue('전송 여부 확인')
    expect(screen.getByRole('button', { name: '메시지 전송' })).toBeEnabled()
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

  it('사용자 역할 조회가 끝나기 전에는 전송을 막는다', () => {
    mockedUseMe.mockReturnValue({ data: undefined, isLoading: true } as ReturnType<typeof useMe>)
    render(<ChatPanel reservationId={11} reservationStatus={ReservationStatus.CONFIRMED} />)

    fireEvent.change(screen.getByLabelText('메시지 입력'), { target: { value: '권한 확인 전' } })
    expect(screen.getByLabelText('메시지 입력')).toBeDisabled()
    expect(screen.getByRole('button', { name: '메시지 전송' })).toBeDisabled()
    expect(screen.getByText('사용자 권한을 확인하는 중이에요.')).toBeInTheDocument()
  })
})
