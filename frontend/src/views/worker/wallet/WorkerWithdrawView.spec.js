import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import WorkerWithdrawView from '@/views/worker/wallet/WorkerWithdrawView.vue'
import { typePin } from '@/test-utils/pinKeypad'

const back = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ back }) }))
vi.mock('@/services/http', () => ({ newIdempotencyKey: () => 'withdrawal-intent-worker' }))

vi.mock('@/services/wallet', () => ({
  fetchTransactions: vi.fn(),
  fetchWallet: vi.fn(),
  withdrawWallet: vi.fn()
}))

import { fetchWallet, withdrawWallet } from '@/services/wallet'

describe('WorkerWithdrawView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    back.mockClear()
    withdrawWallet.mockReset()
    withdrawWallet.mockResolvedValue({
      withdrawalRequestId: 11,
      status: 'COMPLETED',
      bankTransactionId: 21
    })
    fetchWallet.mockReset().mockResolvedValue({
      currency: 'KRW',
      availableBalance: 320000,
      lockedBalance: 0
    })
  })

  it('가용 잔액을 초과하면 출금 버튼이 비활성화된다', async () => {
    const wrapper = mount(WorkerWithdrawView)
    await flushPromises() // 안심지갑 잔액 320,000 로드

    const submit = () => wrapper.find('button.submit')
    expect(submit().attributes('disabled')).toBeDefined()

    await wrapper.find('button.bank').trigger('click') // 은행 선택(첫 은행)
    // 입력 필드: [0] 계좌번호, [1] 금액
    const [accountInput, amountInput] = wrapper.findAll('input')
    await accountInput.setValue('170000000001')

    await amountInput.setValue('500000') // 잔액 초과
    expect(submit().attributes('disabled')).toBeDefined()

    await amountInput.setValue('100000') // 잔액 이내
    expect(submit().attributes('disabled')).toBeUndefined()
  })

  it('유효한 입력이면 확인 모달에서 출금을 요청하고 뒤로 이동한다', async () => {
    // 확인 모달(BaseModal)은 Teleport 로 body 에 렌더되므로 stub 으로 인라인 렌더한다.
    const wrapper = mount(WorkerWithdrawView, { global: { stubs: { teleport: true } } })
    await flushPromises()

    await wrapper.find('button.bank').trigger('click')
    const [accountInput, amountInput] = wrapper.findAll('input')
    await accountInput.setValue('170-0000-00001')
    await amountInput.setValue('100000')

    // 출금하기 → 아직 API 호출 없이 확인 모달만 연다.
    await wrapper.find('button.submit').trigger('click')
    await flushPromises()
    expect(withdrawWallet).not.toHaveBeenCalled()

    // 모달의 '출금하기'(두 번째 modal-btn) 확인 → 실제 출금 요청.
    await typePin(wrapper, '0000')
    const confirmButtons = wrapper.findAll('button.modal-btn')
    await confirmButtons[confirmButtons.length - 1].trigger('click')
    await flushPromises()

    expect(withdrawWallet).toHaveBeenCalledWith(
      expect.objectContaining({ bankCode: '004', accountNo: '170000000001', amount: 100000 }),
      { idempotencyKey: 'withdrawal-intent-worker' }
    )
    expect(fetchWallet).toHaveBeenCalledTimes(2)
    expect(back).toHaveBeenCalled()
  })

  it('불확실한 결과를 수동 재시도할 때 같은 멱등키를 유지한다', async () => {
    withdrawWallet.mockRejectedValueOnce(new Error('network')).mockResolvedValueOnce({
      withdrawalRequestId: 11,
      status: 'COMPLETED',
      bankTransactionId: 21
    })
    const wrapper = mount(WorkerWithdrawView, { global: { stubs: { teleport: true } } })
    await flushPromises()

    await wrapper.find('button.bank').trigger('click')
    const [accountInput, amountInput] = wrapper.findAll('input')
    await accountInput.setValue('170-0000-00001')
    await amountInput.setValue('100000')
    await wrapper.find('button.submit').trigger('click')
    await typePin(wrapper, '0000')
    const confirm = () => {
      const buttons = wrapper.findAll('button.modal-btn')
      return buttons[buttons.length - 1]
    }
    await confirm().trigger('click')
    await flushPromises()
    await confirm().trigger('click')
    await flushPromises()

    expect(withdrawWallet).toHaveBeenCalledTimes(2)
    expect(withdrawWallet.mock.calls[0][1]).toEqual({ idempotencyKey: 'withdrawal-intent-worker' })
    expect(withdrawWallet.mock.calls[1][1]).toEqual({ idempotencyKey: 'withdrawal-intent-worker' })
  })
})
