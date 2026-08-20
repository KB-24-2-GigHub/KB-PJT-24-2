/**
 * 알바생 비밀번호 변경 화면 — 오류 귀속 계약 테스트.
 * 이전에는 원인과 무관하게 모든 실패를 '현재 비밀번호가 일치하지 않아요' 로 단정했다 —
 * 서버가 실제로 그 필드를 지목했을 때만 그 문구를 보여줘야 한다.
 */
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const back = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ back }) }))
vi.mock('@/services/users', () => ({ changePassword: vi.fn() }))

import { changePassword } from '@/services/users'
import { useUiStore } from '@/stores/ui'
import WorkerPasswordEditView from '@/views/worker/mypage/WorkerPasswordEditView.vue'
import { PASSWORD_MAX_LENGTH, PASSWORD_MIN_LENGTH } from '@/utils/validators'

/** [0] 현재 비밀번호 [1] 새 비밀번호 [2] 새 비밀번호 확인 */
async function fillValidForm(wrapper) {
  const inputs = wrapper.findAll('input')
  await inputs[0].setValue('current-pw1')
  await inputs[1].setValue('newpassword1')
  await inputs[2].setValue('newpassword1')
}

describe('WorkerPasswordEditView 오류 귀속', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    back.mockClear()
    changePassword.mockReset()
  })

  it('필드를 지목하지 않은 실패는 현재 비밀번호 오류로 표시하지 않는다', async () => {
    changePassword.mockRejectedValue({ response: { status: 500, data: {} } })
    const toastSpy = vi.spyOn(useUiStore(), 'toast')

    const wrapper = mount(WorkerPasswordEditView)
    await fillValidForm(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.findAll('.field')[0].find('.msg.error').exists()).toBe(false)
    expect(toastSpy).toHaveBeenCalledWith('비밀번호 변경에 실패했어요.', { type: 'danger' })
  })

  it('서버가 현재 비밀번호 필드를 지목하면 그 필드 아래에 표시한다', async () => {
    const fieldError = { field: 'currentPassword', reason: '현재 비밀번호가 올바르지 않습니다.' }
    changePassword.mockRejectedValue({
      response: { status: 400, data: { fieldErrors: [fieldError] } },
      fieldErrors: [fieldError]
    })

    const wrapper = mount(WorkerPasswordEditView)
    await fillValidForm(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.findAll('.field')[0].find('.msg.error').text()).toBe(
      '현재 비밀번호가 올바르지 않습니다.'
    )
  })

  it('서버가 새 비밀번호 필드를 지목하면 그 필드 아래에 표시하고 토스트는 띄우지 않는다', async () => {
    const fieldError = { field: 'newPassword', reason: '이전에 사용한 비밀번호는 쓸 수 없습니다.' }
    changePassword.mockRejectedValue({
      response: { status: 400, data: { fieldErrors: [fieldError] } },
      fieldErrors: [fieldError]
    })
    const toastSpy = vi.spyOn(useUiStore(), 'toast')

    const wrapper = mount(WorkerPasswordEditView)
    await fillValidForm(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.findAll('.field')[1].find('.msg.error').text()).toBe(
      '이전에 사용한 비밀번호는 쓸 수 없습니다.'
    )
    expect(toastSpy).not.toHaveBeenCalled()
  })

  it('fieldErrors 없이 message 만 있으면 필드가 아니라 폼 레벨 토스트로 보여준다', async () => {
    changePassword.mockRejectedValue({
      response: { status: 500, data: { message: '잠시 후 다시 시도해주세요.' } }
    })
    const toastSpy = vi.spyOn(useUiStore(), 'toast')

    const wrapper = mount(WorkerPasswordEditView)
    await fillValidForm(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.findAll('.field')[0].find('.msg.error').exists()).toBe(false)
    expect(toastSpy).toHaveBeenCalledWith('잠시 후 다시 시도해주세요.', { type: 'danger' })
  })

  it('성공하면 토스트를 보여주고 이전 화면으로 돌아간다', async () => {
    changePassword.mockResolvedValue()
    const toastSpy = vi.spyOn(useUiStore(), 'toast')

    const wrapper = mount(WorkerPasswordEditView)
    await fillValidForm(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(toastSpy).toHaveBeenCalledWith('비밀번호가 변경됐어요.', { type: 'success' })
    expect(back).toHaveBeenCalledTimes(1)
  })
})

// #187 로 PATCH /api/users/me/password 가 살아나 준비 중 게이트를 제거했다.
// 게이트가 되돌아오면(제출 비활성화·안내 문구) 이 테스트가 잡는다.
describe('WorkerPasswordEditView 게이트 해제', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('준비 중 안내 없이 제출할 수 있다', () => {
    const wrapper = mount(WorkerPasswordEditView)

    expect(wrapper.text()).not.toContain('비밀번호 변경은 준비 중입니다')
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeUndefined()
  })
})

// 실시간 검증(#238): 배선은 AuthSignupForm 과 같은 패턴이어야 한다.
describe('WorkerPasswordEditView 실시간 검증(#238)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  // Finding 1: 서버가 currentPassword 를 지목한 뒤 사용자가 newPassword(관계없는 필드)를
  // 고쳐도 메시지는 남아 있어야 한다. handleSubmit 이 validateAll() 로 이미 모든 필드를
  // touched 로 올려놨으므로, 서버 오류를 errors 슬롯에 쓰면 newPassword 를 고치는 순간
  // 재검증 watcher 가 currentPassword 규칙(형식은 여전히 유효)을 다시 평가해 지워버린다.
  it('서버가 지목한 현재 비밀번호 오류는 새 비밀번호를 고쳐도 사라지지 않는다', async () => {
    const fieldError = { field: 'currentPassword', reason: '현재 비밀번호가 올바르지 않습니다.' }
    changePassword.mockRejectedValue({
      response: { status: 400, data: { fieldErrors: [fieldError] } },
      fieldErrors: [fieldError]
    })

    const wrapper = mount(WorkerPasswordEditView)
    await fillValidForm(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(wrapper.text()).toContain('현재 비밀번호가 올바르지 않습니다.')

    // currentPassword 가 아니라 newPassword(관계없는 필드)를 고친다. 확인란도 같이 맞춰
    // passwordsMatch 교차 규칙이 끼어들지 않게 한다 — 순수하게 F1 버그만 본다.
    await wrapper.findAll('input')[1].setValue('newpassword2')
    await wrapper.findAll('input')[2].setValue('newpassword2')

    expect(wrapper.text()).toContain('현재 비밀번호가 올바르지 않습니다.')
  })

  it('새 비밀번호 필드를 떠나면 형식 오류가 뜨고 고치면 사라진다', async () => {
    const wrapper = mount(WorkerPasswordEditView)
    // [0] 현재 비밀번호 [1] 새 비밀번호 [2] 새 비밀번호 확인
    const newPassword = wrapper.findAll('input')[1]

    await newPassword.setValue('abc')
    await newPassword.trigger('blur')
    expect(wrapper.text()).toContain(
      `비밀번호는 ${PASSWORD_MIN_LENGTH}~${PASSWORD_MAX_LENGTH}자여야 합니다.`
    )

    await newPassword.setValue('validpassword1')
    expect(wrapper.text()).not.toContain(
      `비밀번호는 ${PASSWORD_MIN_LENGTH}~${PASSWORD_MAX_LENGTH}자여야 합니다.`
    )
  })

  it('새 비밀번호를 고치면 확인란의 불일치 오류도 사라진다', async () => {
    const wrapper = mount(WorkerPasswordEditView)
    const inputs = wrapper.findAll('input')
    const newPassword = inputs[1]
    const confirm = inputs[2]

    await newPassword.setValue('abcdefgh')
    await confirm.setValue('mismatch1')
    await confirm.trigger('blur')
    expect(wrapper.text()).toContain('비밀번호가 일치하지 않습니다.')

    // 확인란이 아니라 새 비밀번호 쪽을 고쳤다.
    await newPassword.setValue('mismatch1')

    expect(wrapper.text()).not.toContain('비밀번호가 일치하지 않습니다.')
  })
})
