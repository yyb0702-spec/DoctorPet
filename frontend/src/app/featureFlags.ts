// 프론트 기능 플래그. 백엔드 미착수 화면을 메뉴·라우트에서 한 번에 껐다 켠다.

// 병원 운영 화면(결제 관리·슬롯 관리) 백엔드 착수 여부.
// GET /api/hospital/payments·GET/PATCH /api/hospital/slots* 가 develop에 병합되면 true로 바꾼다.
// 메뉴(StaffLayout)와 라우트(router)를 이 한 곳으로 함께 제어해,
// "메뉴는 숨겼는데 라우트만 남아 URL 직접 접근 시 404" 같은 불일치를 막는다(PR #127 리뷰).
export const HOSPITAL_OPS_BACKEND_READY = false
