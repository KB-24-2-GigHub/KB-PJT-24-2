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

/** 목록 조회. `page`·`size` 만 받는다(SPEC-382-01). */
export function listNotifications(params = {}) {
  return invokeLive('listNotifications', [params])
}

export function getUnreadCount() {
  return invokeLive('getUnreadCount')
}

export function markNotificationRead(notificationId) {
  return invokeLive('markNotificationRead', [notificationId])
}
