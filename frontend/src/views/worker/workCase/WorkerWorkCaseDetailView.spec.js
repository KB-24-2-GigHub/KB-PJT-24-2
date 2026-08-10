import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { useUiStore } from '@/stores/ui'
import WorkerWorkCaseDetailView from '@/views/worker/workCase/WorkerWorkCaseDetailView.vue'

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { workCaseId: '42' } }),
  useRouter: () => ({ push: vi.fn() })
}))
vi.mock('@/services/workCases', () => ({ getOwnerContact: vi.fn(), getWorkCase: vi.fn() }))

import { getOwnerContact, getWorkCase } from '@/services/workCases'

describe('WorkerWorkCaseDetailView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    getOwnerContact.mockReset().mockResolvedValue({ ownerName: '김사장', phone: '01012345678' })
    getWorkCase.mockReset().mockResolvedValue({
      workCaseId: 42,
      title: '주말 홀 서빙',
      workplaceName: '강남점',
      workDate: '2026-08-20',
      startsAt: '2026-08-20T01:00:00Z',
      endsAt: '2026-08-20T09:00:00Z',
      breakMinutes: 60,
      breakPaid: false,
      dailyWage: 120000,
      status: 'ACCEPTED',
      contract: { documentId: 99, sourceTermsVersion: 3 },
      settlement: { status: 'WAITING' }
    })
  })

  it('실제 상세 DTO의 KST 시각과 같은 계약 최종본 URL을 표시한다', async () => {
    const wrapper = mount(WorkerWorkCaseDetailView)
    await flushPromises()

    expect(wrapper.text()).toContain('10:00 ~ 18:00')
    expect(wrapper.text()).toContain('정산대기')
    expect(wrapper.get('.contract-link').attributes('href')).toBe(
      '/api/documents/99/file?mode=view'
    )
  })

  it('미구현 연락처 Operation을 실패가 아닌 준비 중 상태로 구분한다', async () => {
    getOwnerContact.mockRejectedValueOnce({ code: 'FEATURE_UNAVAILABLE' })
    const wrapper = mount(WorkerWorkCaseDetailView)
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
})
