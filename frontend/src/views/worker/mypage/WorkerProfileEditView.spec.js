/**
 * 알바생 회원정보 변경 화면 — 실시간 검증 배선 테스트(#238).
 *
 * 이 화면은 전화번호 한 필드만 수정 가능하다. AuthSignupForm 과 같은 패턴으로 필드를
 * 떠나면 형식 오류가 뜨고, 고치면 즉시 사라져야 한다.
 */
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const back = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ back }) }))
vi.mock('@/services/users', () => ({ getMe: vi.fn(), updateMe: vi.fn() }))

import { getMe, updateMe } from '@/services/users'
import WorkerProfileEditView from '@/views/worker/mypage/WorkerProfileEditView.vue'

describe('WorkerProfileEditView 실시간 검증(#238)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    back.mockClear()
    getMe.mockReset().mockResolvedValue({
      loginId: 'worker01',
      email: 'worker@test.com',
      name: '김알바',
      phone: ''
    })
    updateMe.mockReset().mockResolvedValue({})
  })

  it('전화번호 필드를 떠나면 형식 오류가 뜨고 고치면 사라진다', async () => {
    const wrapper = mount(WorkerProfileEditView)
    await flushPromises()

    const phone = wrapper.find('input[placeholder="010-0000-0000"]')
    await phone.setValue('123')
    await phone.trigger('blur')
    expect(wrapper.text()).toContain('올바른 전화번호 형식이 아닙니다.')

    await phone.setValue('010-9999-8888')
    expect(wrapper.text()).not.toContain('올바른 전화번호 형식이 아닙니다.')
  })

  it('서버 전화번호를 편집 형식으로 표시한다', async () => {
    getMe.mockResolvedValue({
      loginId: 'worker01',
      email: 'worker@test.com',
      name: '김알바',
      phone: '01098765432'
    })

    const wrapper = mount(WorkerProfileEditView)
    await flushPromises()

    expect(wrapper.find('input[placeholder="010-0000-0000"]').element.value).toBe('010-9876-5432')
  })
})
