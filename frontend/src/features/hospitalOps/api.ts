// 병원 스태프 운영 API (ROLE_HOSPITAL_STAFF). 병원은 서버가 인증 principal로 해석하므로 hospitalId를 보내지 않는다.
import { http } from '@/lib/api/client'
import type { CapabilityValue } from '@/types/enums'
import type {
  HospitalCapabilities,
  OperatingHours,
  OperatingHoursUpdateRequest,
  TemporaryClosure,
} from './types'

export const hospitalOpsApi = {
  getOperatingHours: () =>
    http.get<OperatingHours>('/hospital/operating-hours'),
  updateOperatingHours: (body: OperatingHoursUpdateRequest) =>
    http.put<OperatingHours>('/hospital/operating-hours', body),
  getCapabilities: () =>
    http.get<HospitalCapabilities>('/hospital/capabilities'),
  // 전체 교체(PUT) — 보낸 목록이 그대로 병원의 진료역량이 된다.
  updateCapabilities: (capabilities: CapabilityValue[]) =>
    http.put<HospitalCapabilities>('/hospital/capabilities', { capabilities }),
  createTemporaryClosure: (businessDate: string) =>
    http.post<TemporaryClosure>('/hospital/temporary-closures', {
      businessDate,
    }),
  cancelTemporaryClosure: (businessDate: string) =>
    http.delete<void>(`/hospital/temporary-closures/${businessDate}`),
}
