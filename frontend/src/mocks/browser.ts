// MSW 브라우저 워커. main.tsx가 개발 + VITE_ENABLE_MOCKS=true일 때만 start 한다.
// VITE_FULL_MOCK=true (npm run dev:mock)면 데모 핸들러까지 얹어 백엔드 없이 전체 UI를 체험한다.
import { setupWorker } from 'msw/browser'
import { handlers } from './handlers'
import { demoHandlers } from './demoHandlers'

const fullMock = import.meta.env.VITE_FULL_MOCK === 'true'

export const worker = setupWorker(
  // 데모 핸들러를 앞에 둬 실연동 엔드포인트도 우선 가로챈다.
  ...(fullMock ? demoHandlers : []),
  ...handlers,
)
