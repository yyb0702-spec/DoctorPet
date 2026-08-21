// 저장하지 않은 편집이 있을 때 화면 이탈을 막는다.
//
// 이탈 경로가 두 가지라 둘 다 막아야 한다 — 실제로 더 흔한 쪽은 앞의 것이다.
//  1) 앱 안의 경로 이동(스태프 사이드바 메뉴·링크) → react-router 7의 useBlocker
//  2) 새로고침·탭 닫기 → beforeunload (문구는 브라우저가 정하고 우리가 못 바꾼다)
// useBlocker는 데이터 라우터(createBrowserRouter/createMemoryRouter) 안에서만 쓸 수 있다.
import { useEffect } from 'react'
import { useBlocker } from 'react-router-dom'

const MESSAGE = '저장하지 않은 변경이 있습니다. 저장하지 않고 나가시겠습니까?'

export function useUnsavedChangesWarning(dirty: boolean) {
  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      dirty && currentLocation.pathname !== nextLocation.pathname,
  )

  useEffect(() => {
    if (blocker.state !== 'blocked') return
    if (window.confirm(MESSAGE)) blocker.proceed()
    else blocker.reset()
  }, [blocker])

  useEffect(() => {
    if (!dirty) return
    const warn = (event: BeforeUnloadEvent) => {
      // 최신 브라우저는 preventDefault만 보지만, returnValue를 봐야 경고를 띄우는 구현도 남아 있다.
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])
}
