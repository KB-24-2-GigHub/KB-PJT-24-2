import http from '@/services/http'

export async function listNotifications(params = {}) {
  const { data } = await http.get('/notifications', { params })
  return data
}

export async function markNotificationRead(notificationId) {
  await http.patch(`/notifications/${notificationId}/read`)
}
