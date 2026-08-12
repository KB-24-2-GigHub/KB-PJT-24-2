const notifications = [
  {
    notificationId: 1,
    notiType: 'SETTLED',
    title: '정산 완료',
    content: '임금이 정산되었습니다.',
    isRead: false,
    createdAt: '2026-07-22T18:05:00'
  }
]

export async function listNotifications() {
  return {
    content: notifications.map((notification) => ({ ...notification })),
    unreadCount: notifications.filter((notification) => !notification.isRead).length
  }
}

export async function markNotificationRead(notificationId) {
  const target = notifications.find(
    (notification) => notification.notificationId === notificationId
  )
  if (target) target.isRead = true
}
