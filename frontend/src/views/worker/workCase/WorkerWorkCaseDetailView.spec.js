import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'

import { useUiStore } from '@/stores/ui'
import WorkerWorkCaseDetailView from '@/views/worker/workCase/WorkerWorkCaseDetailView.vue'

const route = reactive({ params: { workCaseId: '42' } })
// AppBackHeader(공통 헤더)가 useRouter를 쓰므로 이 화면이 더 이상 안 써도 함께 목업한다.
vi.mock('vue-router', () => ({ useRoute: () => route, useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/services/workCases', () => ({ getOwnerContact: vi.fn(), getWorkCase: vi.fn() }))

import { getOwnerContact, getWorkCase } from '@/services/workCases'

// GET /api/work-cases/{workCaseId} 실제 계약(escrow·settlement 중첩 객체) 그대로.
function baseWorkCase(overrides = {}) {
  return {
    workCaseId: 42,
    title: '주말 홀 서빙',
    workplaceName: '강남점',
    workDate: '2026-08-20',
    startsAt: '2026-08-20T01:00:00Z',
    endsAt: '2026-08-20T09:00:00Z',
    breakMinutes: 60,
    breakPaid: false,
    dailyWage: 120000,
    status: 'COMPLETED',
    contract: { documentId: 99, sourceTermsVersion: 3 },
    escrow: { status: 'HELD', amount: 120000 },
    settlement: { status: 'WAITING', amount: 120000, dueAt: null, completedAt: null },
    ...overrides
  }
}

// route는 module-level에서 공유되므로, 이전 테스트의 wrapper를 unmount하지 않으면 그
// watch(workCaseId, ...) 이펙트가 살아남아 다음 테스트의 route 변경에도 함께 반응해
// getWorkCase mock 큐를 가로챈다.
let wrapper = null
function mountView() {
  wrapper = mount(WorkerWorkCaseDetailView)
  return wrapper
}

describe('WorkerWorkCaseDetailView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    route.params.workCaseId = '42'
    getOwnerContact.mockReset().mockResolvedValue({ ownerName: '김사장', phone: '01012345678' })
    getWorkCase.mockReset()
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = null
  })

  it('실제 상세 DTO의 KST 시각과 같은 계약 최종본 URL을 표시한다', async () => {
    getWorkCase.mockResolvedValueOnce(baseWorkCase({ status: 'ACCEPTED' }))
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('10:00 ~ 18:00')
    expect(wrapper.text()).toContain('정산대기')
    expect(wrapper.get('.contract-link').attributes('href')).toBe(
      '/api/documents/99/file?mode=view'
    )
  })

  it('미구현 연락처 Operation을 실패가 아닌 준비 중 상태로 구분한다', async () => {
    getWorkCase.mockResolvedValueOnce(baseWorkCase())
    getOwnerContact.mockRejectedValueOnce({ code: 'FEATURE_UNAVAILABLE' })
    const wrapper = mountView()
    await flushPromises()

    const contactButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('사장님께 문의'))
    await contactButton.trigger('click')
    await flushPromises()

    expect(useUiStore().toasts.at(-1)).toMatchObject({
      message: '사장님 연락처는 현재 준비 중인 기능입니다.',
      type: 'info'
    })
  })

  it('workCaseId가 바뀌면 재조회하고 이전 근무의 정산 상태를 재사용하지 않는다', async () => {
    getWorkCase.mockResolvedValueOnce(
      baseWorkCase({ workCaseId: 42, settlement: { status: 'WAITING', amount: 120000 } })
    )
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('정산대기')

    getWorkCase.mockResolvedValueOnce(
      baseWorkCase({
        workCaseId: 77,
        escrow: { status: 'RELEASED', amount: 90000 },
        settlement: {
          status: 'COMPLETED',
          amount: 90000,
          dueAt: '2026-08-21T00:00:00Z',
          completedAt: '2026-08-21T00:05:00Z'
        }
      })
    )
    route.params.workCaseId = '77'
    await flushPromises()

    expect(getWorkCase).toHaveBeenLastCalledWith('77')
    expect(wrapper.text()).toContain('90,000원 지급 완료')
    expect(wrapper.text()).not.toContain('정산대기')
  })

  it('workCaseId 변경 후 조회 실패 시 이전 근무 정보를 유지하지 않는다', async () => {
    getWorkCase.mockResolvedValueOnce(baseWorkCase({ workCaseId: 42 }))
    const wrapper = mountView()
    await flushPromises()
    expect(wrapper.text()).toContain('강남점')

    getWorkCase.mockRejectedValueOnce(new Error('not found'))
    route.params.workCaseId = '99'
    await flushPromises()

    expect(wrapper.find('.detail-card').exists()).toBe(false)
    expect(useUiStore().toasts.at(-1)).toMatchObject({
      message: '근무 정보를 불러오지 못했습니다.',
      type: 'danger'
    })
  })

  it('Escrow와 Settlement 상태를 별도 칩으로 표시한다', async () => {
    getWorkCase.mockResolvedValueOnce(
      baseWorkCase({
        escrow: { status: 'HELD', amount: 120000 },
        settlement: {
          status: 'SCHEDULED',
          amount: 120000,
          dueAt: '2026-08-21T00:00:00Z',
          completedAt: null
        }
      })
    )
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('예치중')
    expect(wrapper.text()).toContain('정산예정')
    expect(wrapper.text()).toContain('2026.08.21 09:00 지급 예정')
  })

  it('정상 지급 완료는 완료 시각과 약정 금액을 표시하고 잔액을 선반영하지 않는다', async () => {
    getWorkCase.mockResolvedValueOnce(
      baseWorkCase({
        escrow: { status: 'RELEASED', amount: 120000 },
        settlement: {
          status: 'COMPLETED',
          amount: 120000,
          dueAt: '2026-08-21T00:00:00Z',
          completedAt: '2026-08-21T00:05:00Z'
        }
      })
    )
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('지급완료')
    expect(wrapper.text()).toContain('2026.08.21 09:05 · 120,000원 지급 완료')
  })

  it('정산 처리 중은 완료로 보이게 표시하지 않는다', async () => {
    getWorkCase.mockResolvedValueOnce(
      baseWorkCase({
        escrow: { status: 'HELD', amount: 120000 },
        settlement: {
          status: 'PROCESSING',
          amount: 120000,
          dueAt: '2026-08-21T00:00:00Z',
          completedAt: null
        }
      })
    )
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('정산 처리 중이에요')
    expect(wrapper.text()).not.toContain('지급 완료')
  })

  it('정산 실패는 지급 완료나 환불로 해석되지 않게 표시한다', async () => {
    getWorkCase.mockResolvedValueOnce(
      baseWorkCase({
        escrow: { status: 'HELD', amount: 120000 },
        settlement: {
          status: 'FAILED',
          amount: 120000,
          dueAt: '2026-08-21T00:00:00Z',
          completedAt: null
        }
      })
    )
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('정산이 실패했어요')
    expect(wrapper.text()).not.toContain('지급 완료')
    expect(wrapper.text()).not.toContain('환불')
  })

  it('노쇼 환불 승인 전에는 획득 금액이 없다고 표시한다', async () => {
    getWorkCase.mockResolvedValueOnce(
      baseWorkCase({
        status: 'NO_SHOW',
        escrow: { status: 'HELD', amount: 120000 },
        settlement: { status: 'WAITING', amount: 120000, dueAt: null, completedAt: null }
      })
    )
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('사장님 환불 승인 대기 중')
    expect(wrapper.text()).toContain('0원')
    expect(wrapper.text()).not.toContain('환불 완료')
  })

  it('노쇼 환불 완료는 OWNER 환불로 표시하고 WORKER 지급으로 합산하지 않는다', async () => {
    getWorkCase.mockResolvedValueOnce(
      baseWorkCase({
        status: 'NO_SHOW',
        escrow: { status: 'REFUNDED', amount: 120000 },
        settlement: {
          status: 'REFUNDED',
          amount: 120000,
          dueAt: null,
          completedAt: '2026-08-21T00:05:00Z'
        }
      })
    )
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('사장님 환불 완료')
    expect(wrapper.text()).toContain('회원님 지급 내역은 없어요')
  })

  it('퇴근 미기록은 결정 전 상태로 표시하고 지급·환불로 해석하지 않는다', async () => {
    getWorkCase.mockResolvedValueOnce(
      baseWorkCase({
        status: 'CHECK_OUT_MISSING',
        escrow: { status: 'HELD', amount: 120000 },
        settlement: { status: 'WAITING', amount: 120000, dueAt: null, completedAt: null }
      })
    )
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('퇴근 기록 확인 중')
    expect(wrapper.text()).not.toContain('지급 완료')
    expect(wrapper.text()).not.toContain('환불')
  })

  it('정산 보류는 서버 상태 그대로 보류로 표시한다', async () => {
    getWorkCase.mockResolvedValueOnce(
      baseWorkCase({
        settlement: {
          status: 'ON_HOLD',
          amount: 120000,
          dueAt: '2026-08-21T00:00:00Z',
          completedAt: null
        }
      })
    )
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('정산보류')
    expect(wrapper.text()).toContain('정산이 보류됐어요')
  })

  it('NO_SHOW·CHECK_OUT_MISSING이어도 ON_HOLD는 서버 상태 그대로 보류로 표시한다', async () => {
    getWorkCase.mockResolvedValueOnce(
      baseWorkCase({
        status: 'NO_SHOW',
        escrow: { status: 'ON_HOLD', amount: 120000 },
        settlement: { status: 'ON_HOLD', amount: 120000, dueAt: null, completedAt: null }
      })
    )
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('정산이 보류됐어요')
    expect(wrapper.text()).not.toContain('환불 승인 대기')
    expect(wrapper.text()).not.toContain('환불 완료')
  })

  it('임금분쟁 신고 버튼은 비활성 상태로만 노출한다', async () => {
    getWorkCase.mockResolvedValueOnce(baseWorkCase())
    const wrapper = mountView()
    await flushPromises()

    const reportButton = wrapper.findAll('button').find((b) => b.text().includes('임금분쟁 신고'))
    expect(reportButton.text()).toContain('준비 중')
    expect(reportButton.attributes('disabled')).toBeDefined()
  })
})
