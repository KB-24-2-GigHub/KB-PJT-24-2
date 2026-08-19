/**
 * 로그인 버튼 클릭 같은 사용자 제스처 "안에서, await 이전에 동기적으로" 불러야 한다 —
 * 그렇지 않으면 브라우저가 조용히 거부한다. iOS Safari 등 미지원 환경에서는 API 자체가
 * 없거나 Promise 가 reject 되므로, 시연용 부가 기능이라 실패를 그냥 삼키고 로그인
 * 흐름에는 영향을 주지 않는다.
 *
 * `.catch()`는 Promise reject만 잡는다 — 일부 환경(권한 없는 iframe 등)은
 * requestFullscreen() 이 동기적으로 throw 하므로, try/catch로 그것도 함께 삼켜야
 * 이 부가 기능이 onSubmit 을 중간에서 끊어 로그인 자체를 막는 사고를 피할 수 있다.
 */
export function requestFullscreenSafely(element = document.documentElement) {
  if (!element?.requestFullscreen) return
  try {
    element.requestFullscreen().catch(() => {})
  } catch {
    // 동기 throw 도 삼킨다 — 시연용 부가 기능이 로그인 흐름을 막으면 안 된다.
  }
}
