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
