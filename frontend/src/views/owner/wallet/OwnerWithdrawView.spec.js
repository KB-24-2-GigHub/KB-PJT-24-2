import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import OwnerWithdrawView from '@/views/owner/wallet/OwnerWithdrawView.vue'

const back = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ back }) }))
vi.mock('@/services/http', () => ({ newIdempotencyKey: () => 'withdrawal-intent-owner' }))

vi.mock('@/services/wallet', () => ({
  fetchTransactions: vi.fn(),
  fetchWallet: vi.fn(),
  withdrawWallet: vi.fn()
}))

import { fetchWallet, withdrawWallet } from '@/services/wallet'

describe('OwnerWithdrawView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    back.mockClear()
    fetchWallet.mockReset().mockResolvedValue({
      currency: 'KRW',
      availableBalance: 320_000,
      lockedBalance: 0
    })
    withdrawWallet.mockReset().mockResolvedValue({
      withdrawalRequestId: 11,
      status: 'COMPLETED',
      bankTransactionId: 21
    })
  })

  it('확인 뒤 canonical 출금 Body를 보내고 공용 지갑을 재조회한다', async () => {
    const wrapper = mount(OwnerWithdrawView, { global: { stubs: { teleport: true } } })
    await flushPromises()

    await wrapper.find('button.bank').trigger('click')
    const [accountInput, amountInput] = wrapper.findAll('input')
    await accountInput.setValue('170-0000-00001')
    await amountInput.setValue('100000')
    await wrapper.find('button.submit').trigger('click')
    await wrapper.find('input[type="password"]').setValue('0000')
    const confirmButtons = wrapper.findAll('button.modal-btn')
    await confirmButtons[confirmButtons.length - 1].trigger('click')
    await flushPromises()

    expect(withdrawWallet).toHaveBeenCalledWith(
      { bankCode: '004', accountNo: '170000000001', amount: 100000 },
      { idempotencyKey: 'withdrawal-intent-owner' }
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
    const wrapper = mount(OwnerWithdrawView, { global: { stubs: { teleport: true } } })
    await flushPromises()

    await wrapper.find('button.bank').trigger('click')
    const [accountInput, amountInput] = wrapper.findAll('input')
    await accountInput.setValue('170-0000-00001')
    await amountInput.setValue('100000')
    await wrapper.find('button.submit').trigger('click')
    await wrapper.find('input[type="password"]').setValue('0000')
    const confirm = () => {
      const buttons = wrapper.findAll('button.modal-btn')
      return buttons[buttons.length - 1]
    }
    await confirm().trigger('click')
    await flushPromises()
    await confirm().trigger('click')
    await flushPromises()

    expect(withdrawWallet).toHaveBeenCalledTimes(2)
    expect(withdrawWallet.mock.calls[0][1]).toEqual({ idempotencyKey: 'withdrawal-intent-owner' })
    expect(withdrawWallet.mock.calls[1][1]).toEqual({ idempotencyKey: 'withdrawal-intent-owner' })
  })
})
