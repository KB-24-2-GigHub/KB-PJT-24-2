import { defineStore } from 'pinia'
import { ref } from 'vue'

import {
  getUnreadCount,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  openNotificationStream
} from '@/services/notifications'

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
  // 전체 읽음 진행 중 여부. 버튼을 연달아 눌러도 요청을 한 번만 만든다.
  let markAllInFlight = false
  // 실시간 구독 연결. 상단 바가 여러 번 마운트돼도 하나만 유지한다.
  let stream = null

  /**
   * 목록은 안읽음만 조회한다(SPEC-423-01).
   *
   * 읽은 항목을 화면 배열에서만 걷어내면 모달을 닫았다 열 때 이 조회가 전체를 다시 받아와
   * 되살아난다. "누르면 사라진다"를 유지하려면 서버 조회 자체가 안읽음으로 좁혀져야 한다.
   */
  async function load() {
    loading.value = true
    try {
      const page = await listNotifications({ unreadOnly: true })
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
   *
   * 성공하면 항목을 목록에서 제거한다(SPEC-423-01). 목록은 안읽음만 담으므로 읽은 항목이
   * 남아 있을 자리가 없다.
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
    items.value = items.value.filter((item) => item.notificationId !== notificationId)
    unreadCount.value = Math.max(0, unreadCount.value - 1)
  }

  /**
   * 전체 읽음 (SPEC-423-01).
   *
   * 항목마다 `markRead` 를 부르지 않는다. 안읽음이 여러 Page 에 걸쳐 있으면 화면에 없는
   * 알림은 영영 남고, 배지는 0이 되지 않는다. 서버가 한 번에 처리한다.
   *
   * 서버 성공 뒤에만 화면을 비운다. 단건과 같은 이유다.
   */
  async function markAllRead() {
    if (markAllInFlight) return
    markAllInFlight = true
    try {
      await markAllNotificationsRead()
    } catch {
      // 실패해도 목록은 그대로 둔다. 다시 누르면 재시도된다.
      return
    } finally {
      markAllInFlight = false
    }
    items.value = []
    unreadCount.value = 0
  }

  /**
   * 실시간 알림 구독 (#386).
   *
   * 서버가 보내는 것은 "다시 조회하라"는 신호뿐이고 알림 본문은 오지 않는다. 계약은
   * 목록·개수 Endpoint 가 이미 소유하고 있어서, 스트림으로 두 번째 형태를 만들면 같은 알림을
   * 두 벌로 관리하게 된다.
   *
   * 연결이 실패해도 화면은 그대로 동작해야 한다. onerror 에서 아무것도 하지 않는 이유는
   * EventSource 가 스스로 재연결하기 때문이고, 그 사이에도 모달을 열면 목록은 조회된다.
   */
  function connect() {
    if (stream || typeof EventSource === 'undefined') return
    try {
      stream = openNotificationStream()
    } catch {
      // 구독 실패는 배지가 늦게 갱신될 뿐이다. 상단 바를 깨뜨리지 않는다.
      stream = null
      return
    }
    stream.addEventListener('notification', () => {
      loadUnreadCount()
      // 모달이 열려 있을 때만 목록을 다시 읽는다. 닫혀 있으면 열 때 어차피 조회한다.
      if (isOpen.value) load()
    })
  }

  function disconnect() {
    stream?.close()
    stream = null
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
    markAllRead,
    connect,
    disconnect,
    open,
    close
  }
})
