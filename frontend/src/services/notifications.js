import * as api from '@/services/api/notificationsApi'
import { invokeOperation } from '@/services/operationAdapter'

/**
 * 알림 Endpoint 는 #384 로 실제 구현됐다.
 *
 * Mock 은 제거했다. `notifications` 테이블이 없던 동안 화면을 붙잡아 두려고 두었던 것인데,
 * 이제 실 응답 형태(`{content,page}`)와 달라 오히려 계약을 가린다.
 */
function invokeLive(method, args = []) {
  return invokeOperation({
    operation: `notifications.${method}`,
    api: api[method],
    args
  })
}

/** 목록 조회. `page`·`size`·`unreadOnly` 를 받는다(SPEC-382-01, SPEC-423-01). */
export function listNotifications(params = {}) {
  return invokeLive('listNotifications', [params])
}

export function getUnreadCount() {
  return invokeLive('getUnreadCount')
}

export function markNotificationRead(notificationId) {
  return invokeLive('markNotificationRead', [notificationId])
}

export function markAllNotificationsRead() {
  return invokeLive('markAllNotificationsRead')
}

/**
 * 실시간 스트림 구독 (#386).
 *
 * `invokeOperation` 을 거치지 않는다. 그 어댑터는 Promise 를 돌려주는 Operation 의 Mock·LIVE
 * 전환 경계인데, 이것은 즉시 EventSource 를 돌려주는 연결이고 Mock 대상도 아니다.
 */
export { openNotificationStream } from '@/services/api/notificationsApi'
