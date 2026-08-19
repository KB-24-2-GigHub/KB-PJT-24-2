/**
 * 로그인 버튼 클릭 같은 사용자 제스처 "안에서, await 이전에 동기적으로" 불러야 한다 —
 * 그렇지 않으면 브라우저가 조용히 거부한다. iOS Safari 등 미지원 환경에서는 API 자체가
 * 없거나 Promise 가 reject 되므로, 시연용 부가 기능이라 실패를 그냥 삼키고 로그인
 * 흐름에는 영향을 주지 않는다.
 */
export function requestFullscreenSafely(element = document.documentElement) {
  if (!element?.requestFullscreen) return
  element.requestFullscreen().catch(() => {})
}
