// 병원 스태프 회원 이력 조회 훅. 섹션이 열렸을 때만 불러온다.
import { useQuery } from '@tanstack/react-query'
import { staffMemberHistoryApi } from './api'

export function useMemberHistory(
  reservationId: number,
  page: number,
  enabled: boolean,
) {
  return useQuery({
    queryKey: ['staff-member-history', reservationId, page],
    queryFn: () => staffMemberHistoryApi.getMemberHistory(reservationId, page),
    enabled: enabled && Number.isFinite(reservationId),
  })
}
