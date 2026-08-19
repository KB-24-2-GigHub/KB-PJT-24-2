import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import OwnerChargeConfirmView from '@/views/owner/wallet/OwnerChargeConfirmView.vue'

const replace = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ replace }) }))
vi.mock('@/services/http', () => ({ newIdempotencyKey: () => 'funding-intent-203' }))
vi.mock('@/services/wallet', () => ({
  chargeWallet: vi.fn(),
  fetchTransactions: vi.fn(),
  fetchWallet: vi.fn()
}))

import { chargeWallet, fetchTransactions, fetchWallet } from '@/services/wallet'
import { useWalletFundingStore } from '@/stores/walletFunding'

function prepareDraft() {
  useWalletFundingStore().setDraft({
    bankCode: '004',
    accountNo: '170000000001',
    amount: 100_000
  })
}

describe('OwnerChargeConfirmView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    replace.mockReset().mockResolvedValue()
    chargeWallet.mockReset().mockResolvedValue({
      fundingOrderId: 10,
      status: 'COMPLETED',
      bankTransactionId: 20
    })
    fetchWallet.mockReset().mockResolvedValue({
      currency: 'KRW',
      availableBalance: 1_350_000,
      lockedBalance: 480_000
    })
    fetchTransactions.mockReset().mockResolvedValue({
      content: [],
      page: { number: 0, size: 20, totalElements: 0, totalPages: 0 }
    })
  })

  it('은행·계좌·금액을 표시하고 정확한 네 필드로 한 번만 충전한다', async () => {
    prepareDraft()
    const wrapper = mount(OwnerChargeConfirmView)
    const fundingStore = useWalletFundingStore()

    expect(wrapper.text()).toContain('KB국민은행')
    expect(wrapper.text()).toContain('170000000001')
    expect(wrapper.text()).toContain('100,000원')

    const pinInput = wrapper.find('input[type="password"]')
    await pinInput.setValue('0000')
    await wrapper.find('button.confirm').trigger('click')
    await flushPromises()

    expect(chargeWallet).toHaveBeenCalledOnce()
    expect(chargeWallet).toHaveBeenCalledWith(
      { bankCode: '004', accountNo: '170000000001', pin: '0000', amount: 100_000 },
      { idempotencyKey: 'funding-intent-203' }
    )
    expect(Object.keys(chargeWallet.mock.calls[0][0]).sort()).toEqual([
      'accountNo',
      'amount',
      'bankCode',
      'pin'
    ])
    expect(fetchWallet).toHaveBeenCalledOnce()
    expect(fetchTransactions).toHaveBeenCalledOnce()
    expect(fetchTransactions).toHaveBeenCalledWith({ sort: 'LATEST', page: 0, size: 20 })
    expect(wrapper.vm.$.setupState.pin).toBe('')
    expect(wrapper.find('input[type="password"]').exists()).toBe(false)
    expect(fundingStore.draft).toBeNull()
    expect(replace).toHaveBeenCalledWith({ name: 'owner-home' })
  })

  it('3자리·비숫자 PIN은 제출하지 않고 5번째 숫자는 저장하지 않는다', async () => {
    prepareDraft()
    const wrapper = mount(OwnerChargeConfirmView)
    const pinInput = wrapper.find('input[type="password"]')

    await pinInput.setValue('12a')
    expect(pinInput.element.value).toBe('12')
    expect(wrapper.find('button.confirm').attributes('disabled')).toBeDefined()

    await pinInput.setValue('12345')
    expect(pinInput.element.value).toBe('1234')
    expect(wrapper.find('button.confirm').attributes('disabled')).toBeUndefined()
    expect(chargeWallet).not.toHaveBeenCalled()
  })

  it('전달 초안이 없으면 임의 요청 없이 충전 입력 화면으로 복귀한다', async () => {
    mount(OwnerChargeConfirmView)
    await flushPromises()

    expect(chargeWallet).not.toHaveBeenCalled()
    expect(replace).toHaveBeenCalledWith({ name: 'owner-charge' })
  })

  it('계좌 인증 실패 후 PIN을 폐기하고 승인 오류를 표시한다', async () => {
    prepareDraft()
    chargeWallet.mockRejectedValue({
      code: 'FORBIDDEN',
      response: {
        status: 403,
        data: { code: 'FORBIDDEN', message: '계좌를 사용할 수 없습니다.' }
      }
    })
    const wrapper = mount(OwnerChargeConfirmView)
    const pinInput = wrapper.find('input[type="password"]')

    await pinInput.setValue('0000')
    await wrapper.find('button.confirm').trigger('click')
    await flushPromises()

    expect(pinInput.element.value).toBe('')
    expect(wrapper.find('[role="alert"]').text()).toBe('계좌를 사용할 수 없습니다.')
    expect(useWalletFundingStore().draft).not.toBeNull()
  })

  it('처리 중 중복 클릭을 막는다', async () => {
    prepareDraft()
    let resolveCharge
    chargeWallet.mockImplementation(() => new Promise((resolve) => (resolveCharge = resolve)))
    const wrapper = mount(OwnerChargeConfirmView)
    await wrapper.find('input[type="password"]').setValue('0000')

    const button = wrapper.find('button.confirm')
    await button.trigger('click')
    await button.trigger('click')

    expect(chargeWallet).toHaveBeenCalledOnce()
    resolveCharge({ fundingOrderId: 10, status: 'COMPLETED', bankTransactionId: 20 })
    await flushPromises()
  })

  it('불확실한 결과를 수동 재시도할 때 같은 멱등키를 유지한다', async () => {
    prepareDraft()
    chargeWallet
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce({ fundingOrderId: 10, status: 'COMPLETED', bankTransactionId: 20 })
    const wrapper = mount(OwnerChargeConfirmView)
    const pinInput = wrapper.find('input[type="password"]')

    await pinInput.setValue('0000')
    await wrapper.find('button.confirm').trigger('click')
    await flushPromises()
    await pinInput.setValue('0000')
    await wrapper.find('button.confirm').trigger('click')
    await flushPromises()

    expect(chargeWallet).toHaveBeenCalledTimes(2)
    expect(chargeWallet.mock.calls[0][1]).toEqual({ idempotencyKey: 'funding-intent-203' })
    expect(chargeWallet.mock.calls[1][1]).toEqual({ idempotencyKey: 'funding-intent-203' })
  })

  it('화면을 이탈하면 PIN과 계좌 초안을 모두 폐기한다', async () => {
    prepareDraft()
    const wrapper = mount(OwnerChargeConfirmView)
    await wrapper.find('input[type="password"]').setValue('0000')

    wrapper.unmount()

    expect(wrapper.vm.$.setupState.pin).toBe('')
    expect(useWalletFundingStore().draft).toBeNull()
  })
})
