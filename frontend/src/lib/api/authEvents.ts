// axios 인터셉터(React 밖)가 강제 로그아웃을 UI에 알리는 얇은 이벤트 채널.
// Refresh 재사용 감지 등으로 세션이 무효화되면 발행한다 (SA §6-1).
type Handler = () => void
const handlers = new Set<Handler>()

export function onSessionExpired(handler: Handler): () => void {
  handlers.add(handler)
  return () => handlers.delete(handler)
}

export function emitSessionExpired() {
  handlers.forEach((h) => h())
}
