import http from '@/services/http'

export async function listNotifications(params = {}) {
  const { data } = await http.get('/notifications', { params })
  return data
}

/**
 * 안읽음 개수 (SPEC-382-01).
 *
 * 목록 Envelope 에 개수를 덧붙이지 않고 별도 Endpoint 로 분리돼 있다. 헤더 종 아이콘은
 * 모달을 열지 않은 상태에서도 개수만 필요하다.
 */
export async function getUnreadCount() {
  const { data } = await http.get('/notifications/unread-count')
  return data
}

export async function markNotificationRead(notificationId) {
  await http.patch(`/notifications/${notificationId}/read`)
}

/**
 * 실시간 알림 스트림 구독 (#386).
 *
 * axios 를 쓰지 않는다. SSE 는 끝나지 않는 응답이라 `timeout: 10_000` 에 걸리고, 브라우저가
 * 끊긴 연결을 자동으로 다시 붙여 주는 것도 EventSource 쪽이다.
 *
 * `withCredentials` 가 필수다. EventSource 는 기본적으로 쿠키를 보내지 않아서, Frontend 와 API
 * Origin 이 다른 배포에서는 이것이 없으면 JSESSIONID 가 빠져 401 로 끊긴다.
 *
 * 반환 객체의 수명(구독 해제)은 호출자가 소유한다.
 */
export function openNotificationStream() {
  const base = import.meta.env.VITE_API_BASE_URL || '/api'
  return new EventSource(`${base}/notifications/stream`, { withCredentials: true })
}
