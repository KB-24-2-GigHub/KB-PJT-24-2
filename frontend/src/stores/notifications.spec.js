/**
 * 알림 스토어 계약 테스트 — SPEC-382-01 응답 형태와 #384 실 API 기준.
 *
 * 여기서 고정하는 것은 세 가지다. 안읽음 개수를 목록에서 세지 않고 별도 Endpoint 로 얻는지,
 * 읽음 처리가 서버 성공 뒤에만 화면을 바꾸는지, 조회 실패가 화면을 깨뜨리지 않는지.
 */
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/notifications', () => ({
  listNotifications: vi.fn(),
  getUnreadCount: vi.fn(),
  markNotificationRead: vi.fn()
}))

import { getUnreadCount, listNotifications, markNotificationRead } from '@/services/notifications'
import { useNotificationsStore } from '@/stores/notifications'

/** 승인 응답 형태 그대로. 목록은 공통 {content,page} Envelope 다. */
function page(content) {
  return {
    content,
    page: { number: 0, size: 20, totalElements: content.length, totalPages: 1 }
  }
}

function notification(overrides = {}) {
  return {
    notificationId: 1,
    notiType: 'SETTLED',
    title: '정산 완료',
    content: "'주말 홀서빙' 근무의 정산이 완료됐어요.",
    sourceType: 'SETTLEMENT',
    sourceId: 8801,
    workCaseId: 77,
    isRead: false,
    readAt: null,
    createdAt: '2026-08-16T09:00:00Z',
    ...overrides
  }
}

describe('notifications store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('목록은 Page Envelope 의 content 만 읽는다', async () => {
    listNotifications.mockResolvedValue(page([notification()]))
    const store = useNotificationsStore()

    await store.load()

    expect(store.items).toHaveLength(1)
    expect(store.items[0].workCaseId).toBe(77)
    expect(store.loadError).toBe(false)
  })

  /*
   * 목록 응답에서 세면 첫 Page 밖의 안읽음 알림이 빠져 배지가 실제보다 작아진다.
   * 그래서 개수는 별도 Endpoint 로만 얻는다.
   */
  it('안읽음 개수를 목록에서 계산하지 않고 별도 Endpoint 로 얻는다', async () => {
    listNotifications.mockResolvedValue(page([notification(), notification({ notificationId: 2 })]))
    getUnreadCount.mockResolvedValue({ unreadCount: 7 })
    const store = useNotificationsStore()

    await store.load()
    expect(store.unreadCount).toBe(0)

    await store.loadUnreadCount()

    expect(store.unreadCount).toBe(7)
    expect(getUnreadCount).toHaveBeenCalledTimes(1)
  })

  it('읽음 처리에 성공하면 항목과 개수를 함께 줄인다', async () => {
    listNotifications.mockResolvedValue(page([notification()]))
    getUnreadCount.mockResolvedValue({ unreadCount: 1 })
    markNotificationRead.mockResolvedValue(undefined)
    const store = useNotificationsStore()
    await store.load()
    await store.loadUnreadCount()

    await store.markRead(1)

    expect(markNotificationRead).toHaveBeenCalledWith(1)
    expect(store.items[0].isRead).toBe(true)
    expect(store.items[0].readAt).not.toBeNull()
    expect(store.unreadCount).toBe(0)
  })

  /*
   * 낙관적으로 먼저 줄이면 실패했을 때 배지와 서버 상태가 어긋난 채 남는다.
   * 모달 항목 클릭은 대기 없이 호출되므로 거절을 밖으로 던지지도 않는다.
   */
  it('읽음 처리가 실패하면 화면 상태를 바꾸지 않는다', async () => {
    listNotifications.mockResolvedValue(page([notification()]))
    getUnreadCount.mockResolvedValue({ unreadCount: 1 })
    markNotificationRead.mockRejectedValue({ response: { status: 500 } })
    const store = useNotificationsStore()
    await store.load()
    await store.loadUnreadCount()

    await expect(store.markRead(1)).resolves.toBeUndefined()

    expect(store.items[0].isRead).toBe(false)
    expect(store.unreadCount).toBe(1)
  })

  it('이미 읽은 알림은 서버를 다시 부르지 않는다', async () => {
    listNotifications.mockResolvedValue(page([notification({ isRead: true })]))
    const store = useNotificationsStore()
    await store.load()

    await store.markRead(1)

    expect(markNotificationRead).not.toHaveBeenCalled()
  })

  it('목록 조회가 실패해도 던지지 않고 오류 상태만 남긴다', async () => {
    listNotifications.mockRejectedValue({ response: { status: 500 } })
    const store = useNotificationsStore()

    await expect(store.load()).resolves.toBeUndefined()

    expect(store.items).toEqual([])
    expect(store.loadError).toBe(true)
    expect(store.loading).toBe(false)
  })

  /* 배지 하나 때문에 상단 바가 깨지면 안 된다. 직전 값을 유지한다. */
  it('개수 조회가 실패하면 직전 값을 유지한다', async () => {
    getUnreadCount.mockResolvedValueOnce({ unreadCount: 3 })
    const store = useNotificationsStore()
    await store.loadUnreadCount()

    getUnreadCount.mockRejectedValueOnce({ response: { status: 500 } })
    await expect(store.loadUnreadCount()).resolves.toBeUndefined()

    expect(store.unreadCount).toBe(3)
  })

  it('모달을 열면 목록을 조회한다', async () => {
    listNotifications.mockResolvedValue(page([]))
    const store = useNotificationsStore()

    store.open()

    expect(store.isOpen).toBe(true)
    expect(listNotifications).toHaveBeenCalledTimes(1)
  })
})
