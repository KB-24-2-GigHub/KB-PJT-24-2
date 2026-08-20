import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import WithdrawConfirmModal from '@/components/wallet/WithdrawConfirmModal.vue'
import { typePin } from '@/test-utils/pinKeypad'

function mountModal(props = {}) {
  return mount(WithdrawConfirmModal, {
    props: {
      open: true,
      bankName: 'KB국민은행',
      accountNo: '170000000001',
      amount: 100_000,
      ...props
    },
    global: { stubs: { teleport: true } }
  })
}

function confirmButton(wrapper) {
  const buttons = wrapper.findAll('button.modal-btn')
  return buttons[buttons.length - 1]
}

describe('WithdrawConfirmModal', () => {
  it('지갑 비밀번호 4자리를 입력하기 전에는 출금하기를 누를 수 없다', async () => {
    const wrapper = mountModal()

    await confirmButton(wrapper).trigger('click')

    expect(wrapper.emitted('confirm')).toBeUndefined()
  })

  it('4자리를 입력하면 출금하기를 눌러 confirm을 올린다', async () => {
    const wrapper = mountModal()

    await typePin(wrapper, '0000')
    await confirmButton(wrapper).trigger('click')

    expect(wrapper.emitted('confirm')).toHaveLength(1)
  })

  it('모달을 닫으면 입력했던 비밀번호를 비운다', async () => {
    const wrapper = mountModal()
    await typePin(wrapper, '12')

    await wrapper.setProps({ open: false })
    await wrapper.setProps({ open: true })

    expect(wrapper.find('input[type="password"]').element.value).toBe('')
  })
})
