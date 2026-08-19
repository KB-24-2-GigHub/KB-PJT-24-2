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
  markNotificationRead: vi.fn(),
  markAllNotificationsRead: vi.fn(),
  openNotificationStream: vi.fn()
}))

import {
  getUnreadCount,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  openNotificationStream
} from '@/services/notifications'
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

  /*
   * 목록은 안읽음만 담으므로(SPEC-423-01) 읽은 항목이 남아 있을 자리가 없다.
   * 화면에서 지우는 것만으로는 부족하고 조회 자체가 좁혀져야 다시 열 때 되살아나지 않는다.
   */
  it('목록을 안읽음만 조회한다', async () => {
    listNotifications.mockResolvedValue(page([notification()]))
    const store = useNotificationsStore()

    await store.load()

    expect(listNotifications).toHaveBeenCalledWith({ unreadOnly: true })
  })

  it('읽음 처리에 성공하면 항목을 목록에서 빼고 개수도 줄인다', async () => {
    listNotifications.mockResolvedValue(page([notification(), notification({ notificationId: 2 })]))
    getUnreadCount.mockResolvedValue({ unreadCount: 2 })
    markNotificationRead.mockResolvedValue(undefined)
    const store = useNotificationsStore()
    await store.load()
    await store.loadUnreadCount()

    await store.markRead(1)

    expect(markNotificationRead).toHaveBeenCalledWith(1)
    expect(store.items.map((item) => item.notificationId)).toEqual([2])
    expect(store.unreadCount).toBe(1)
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

    expect(store.items).toHaveLength(1)
    expect(store.items[0].isRead).toBe(false)
    expect(store.unreadCount).toBe(1)
  })

  /*
   * 화면 상태는 응답 뒤에 바뀐다. 같은 항목을 빠르게 두 번 누르면 isRead 검사만으로는
   * 두 호출이 모두 통과해 배지가 두 번 줄어든다.
   */
  it('같은 알림을 동시에 두 번 눌러도 한 번만 호출하고 개수도 한 번만 줄인다', async () => {
    listNotifications.mockResolvedValue(page([notification()]))
    getUnreadCount.mockResolvedValue({ unreadCount: 2 })
    let resolveRead
    markNotificationRead.mockReturnValue(
      new Promise((resolve) => {
        resolveRead = resolve
      })
    )
    const store = useNotificationsStore()
    await store.load()
    await store.loadUnreadCount()

    const first = store.markRead(1)
    const second = store.markRead(1)
    resolveRead()
    await Promise.all([first, second])

    expect(markNotificationRead).toHaveBeenCalledTimes(1)
    expect(store.items).toHaveLength(0)
    expect(store.unreadCount).toBe(1)
  })

  it('이미 읽은 알림은 서버를 다시 부르지 않는다', async () => {
    listNotifications.mockResolvedValue(page([notification({ isRead: true })]))
    const store = useNotificationsStore()
    await store.load()

    await store.markRead(1)

    expect(markNotificationRead).not.toHaveBeenCalled()
  })

  /*
   * 전체 읽음 (SPEC-423-01).
   *
   * 항목마다 markRead 를 부르면 안읽음이 여러 Page 에 걸쳐 있을 때 화면에 없는 알림이 남고
   * 배지가 0이 되지 않는다. 서버가 한 번에 처리한다.
   */
  describe('전체 읽음', () => {
    it('한 번의 호출로 목록을 비우고 배지를 0으로 만든다', async () => {
      listNotifications.mockResolvedValue(
        page([notification(), notification({ notificationId: 2 })])
      )
      getUnreadCount.mockResolvedValue({ unreadCount: 9 })
      markAllNotificationsRead.mockResolvedValue(undefined)
      const store = useNotificationsStore()
      await store.load()
      await store.loadUnreadCount()

      await store.markAllRead()

      expect(markAllNotificationsRead).toHaveBeenCalledTimes(1)
      expect(markNotificationRead).not.toHaveBeenCalled()
      expect(store.items).toEqual([])
      expect(store.unreadCount).toBe(0)
    })

    it('실패하면 목록과 배지를 그대로 둔다', async () => {
      listNotifications.mockResolvedValue(page([notification()]))
      getUnreadCount.mockResolvedValue({ unreadCount: 1 })
      markAllNotificationsRead.mockRejectedValue({ response: { status: 500 } })
      const store = useNotificationsStore()
      await store.load()
      await store.loadUnreadCount()

      await expect(store.markAllRead()).resolves.toBeUndefined()

      expect(store.items).toHaveLength(1)
      expect(store.unreadCount).toBe(1)
    })

    it('연달아 눌러도 요청을 한 번만 만든다', async () => {
      listNotifications.mockResolvedValue(page([notification()]))
      let resolveAll
      markAllNotificationsRead.mockReturnValue(
        new Promise((resolve) => {
          resolveAll = resolve
        })
      )
      const store = useNotificationsStore()
      await store.load()

      const first = store.markAllRead()
      const second = store.markAllRead()
      resolveAll()
      await Promise.all([first, second])

      expect(markAllNotificationsRead).toHaveBeenCalledTimes(1)
    })
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

  /*
   * 실시간 구독(#386).
   *
   * 서버가 보내는 것은 "다시 조회하라"는 신호뿐이다. 스트림에서 알림 본문을 읽어 화면에 넣으면
   * 목록 Endpoint 와 형태가 두 벌이 된다. 그리고 SSE 가 죽어도 목록·읽음 처리는 살아 있어야
   * 한다 — 이것이 SSE 를 마지막 단계로 둔 이유다.
   */
  describe('실시간 구독', () => {
    /** jsdom 에는 EventSource 가 없다. 구독·해제만 관찰하는 최소 대역을 세운다. */
    function fakeEventSource() {
      const listeners = {}
      const source = {
        addEventListener: (name, handler) => {
          listeners[name] = handler
        },
        close: vi.fn(),
        emit: (name) => listeners[name]?.()
      }
      openNotificationStream.mockReturnValue(source)
      globalThis.EventSource = function EventSourceStub() {}
      return source
    }

    it('신호를 받으면 배지를 다시 조회한다', async () => {
      const source = fakeEventSource()
      getUnreadCount.mockResolvedValue({ unreadCount: 4 })
      const store = useNotificationsStore()

      store.connect()
      source.emit('notification')
      await vi.waitFor(() => expect(store.unreadCount).toBe(4))
    })

    it('모달이 닫혀 있으면 목록까지 다시 읽지 않는다', async () => {
      const source = fakeEventSource()
      getUnreadCount.mockResolvedValue({ unreadCount: 1 })
      const store = useNotificationsStore()

      store.connect()
      source.emit('notification')

      expect(listNotifications).not.toHaveBeenCalled()
    })

    it('모달이 열려 있으면 목록도 함께 갱신한다', async () => {
      const source = fakeEventSource()
      getUnreadCount.mockResolvedValue({ unreadCount: 1 })
      listNotifications.mockResolvedValue(page([]))
      const store = useNotificationsStore()

      store.open()
      store.connect()
      source.emit('notification')

      expect(listNotifications).toHaveBeenCalledTimes(2)
    })

    it('상단 바가 다시 마운트돼도 연결은 하나만 유지한다', () => {
      fakeEventSource()
      const store = useNotificationsStore()

      store.connect()
      store.connect()

      expect(openNotificationStream).toHaveBeenCalledTimes(1)
    })

    it('해제하면 연결을 닫는다', () => {
      const source = fakeEventSource()
      const store = useNotificationsStore()

      store.connect()
      store.disconnect()

      expect(source.close).toHaveBeenCalledTimes(1)
    })

    /* 구독이 실패해도 목록 조회는 그대로 동작해야 한다. */
    it('구독에 실패해도 던지지 않고 목록 조회는 계속 동작한다', async () => {
      globalThis.EventSource = function EventSourceStub() {}
      openNotificationStream.mockImplementation(() => {
        throw new Error('connection refused')
      })
      listNotifications.mockResolvedValue(page([notification()]))
      const store = useNotificationsStore()

      expect(() => store.connect()).not.toThrow()

      await store.load()
      expect(store.items).toHaveLength(1)
      expect(store.loadError).toBe(false)
    })
  })
})
