import { defineStore } from 'pinia'
import { ref } from 'vue'

import { getUnreadCount, listNotifications, markNotificationRead } from '@/services/notifications'

/**
 * 알림 상태 — 공통. 헤더 종 아이콘(안읽음 배지 + 모달 열기)과 NotificationModal 이 공유한다.
 *
 * 안읽음 개수는 목록과 별도 Endpoint 다(SPEC-382-01). 목록 응답에서 세면 첫 Page 밖의 안읽음
 * 알림이 빠져 배지가 실제보다 작아진다. 그래서 모달을 열지 않아도 개수만 따로 조회한다.
 */
export const useNotificationsStore = defineStore('notifications', () => {
  const items = ref([])
  const unreadCount = ref(0)
  const isOpen = ref(false)
  const loading = ref(false)
  const loadError = ref(false)
  // 처리 중인 notificationId. 같은 항목을 연달아 눌러도 요청과 개수 차감을 한 번만 만든다.
  const readInFlight = new Set()

  async function load() {
    loading.value = true
    try {
      const page = await listNotifications()
      items.value = page?.content ?? []
      loadError.value = false
    } catch {
      // 알림 실패가 화면 전체를 막지 않는다. 목록만 비우고 모달이 안내를 보여준다.
      items.value = []
      loadError.value = true
    } finally {
      loading.value = false
    }
  }

  /** 배지 전용 조회. 목록을 열지 않는 화면에서도 개수는 최신이어야 한다. */
  async function loadUnreadCount() {
    try {
      const result = await getUnreadCount()
      unreadCount.value = result?.unreadCount ?? 0
    } catch {
      // 배지 하나 때문에 상단 바를 깨뜨리지 않는다. 직전 값을 유지한다.
    }
  }

  /**
   * 읽음 처리는 서버 성공 뒤에만 화면을 바꾼다.
   *
   * 먼저 낙관적으로 줄이면 실패했을 때 배지와 서버 상태가 어긋난 채 남는다. 이미 읽은 알림에
   * 다시 요청해도 서버는 성공을 돌려주므로, 화면에서 안읽음일 때만 개수를 줄인다.
   *
   * 화면 상태는 응답 뒤에 바뀌므로 `isRead` 검사만으로는 같은 항목을 빠르게 두 번 눌렀을 때
   * 두 호출이 모두 통과해 개수가 두 번 줄어든다. 처리 중인 식별자를 따로 들고 막는다.
   */
  async function markRead(notificationId) {
    const target = items.value.find((item) => item.notificationId === notificationId)
    if (!target || target.isRead || readInFlight.has(notificationId)) return
    readInFlight.add(notificationId)
    try {
      await markNotificationRead(notificationId)
    } catch {
      // 모달 항목 클릭은 대기 없이 호출된다. 실패를 던지면 처리되지 않은 거절만 남으므로
      // 화면 상태를 그대로 두어 다음 클릭에 다시 시도할 수 있게 한다.
      return
    } finally {
      readInFlight.delete(notificationId)
    }
    target.isRead = true
    target.readAt = new Date().toISOString()
    unreadCount.value = Math.max(0, unreadCount.value - 1)
  }

  function open() {
    isOpen.value = true
    load()
  }
  function close() {
    isOpen.value = false
  }

  return {
    items,
    unreadCount,
    isOpen,
    loading,
    loadError,
    load,
    loadUnreadCount,
    markRead,
    open,
    close
  }
})
