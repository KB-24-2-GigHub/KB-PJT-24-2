import * as api from '@/services/api/notificationsApi'
import { invokeOperation, OPERATION_SUPPORT } from '@/services/operationAdapter'

const loadMock = import.meta.env.DEV ? () => import('@/mocks/notificationsMockApi') : null

function invoke(method, args = []) {
  return invokeOperation({
    operation: `notifications.${method}`,
    support: OPERATION_SUPPORT.UNAVAILABLE,
    ownerIssue: '#167/#176',
    api: api[method],
    loadMock,
    mockMethod: method,
    args
  })
}

export function listNotifications(params = {}) {
  return invoke('listNotifications', [params])
}

export function markNotificationRead(notificationId) {
  return invoke('markNotificationRead', [notificationId])
}
