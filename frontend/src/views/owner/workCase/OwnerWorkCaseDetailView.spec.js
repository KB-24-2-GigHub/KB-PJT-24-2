import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import OwnerWorkCaseDetailView from '@/views/owner/workCase/OwnerWorkCaseDetailView.vue'

const push = vi.fn()
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { workCaseId: '42' } }),
  useRouter: () => ({ push })
}))

vi.mock('@/services/workCases', () => ({
  getWorkCase: vi.fn(),
  updateWorkCase: vi.fn(),
  deleteWorkCase: vi.fn(),
  listReports: vi.fn(),
  createInvite: vi.fn(),
  reissueInvite: vi.fn(),
  approveSettlement: vi.fn(),
  approveNoShowRefund: vi.fn()
}))
vi.mock('@/services/http', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, newIdempotencyKey: vi.fn(() => 'settlement-intent-key') }
})
vi.mock('@/services/wallet', () => ({
  fetchWallet: vi.fn(),
  fetchTransactions: vi.fn()
}))
vi.mock('@/utils/clipboard', () => ({ copyText: vi.fn().mockResolvedValue(true) }))

import { newIdempotencyKey } from '@/services/http'
import { fetchTransactions, fetchWallet } from '@/services/wallet'
import {
  approveNoShowRefund,
  approveSettlement,
  createInvite,
  deleteWorkCase,
  getWorkCase,
  listReports,
  reissueInvite,
  updateWorkCase
} from '@/services/workCases'
import { useUiStore } from '@/stores/ui'

// startsAt 2026-08-01T00:00:00Z = KST 09:00. 아래 고정 시각(7/22)은 그보다 앞이라 "시작 전"이다.
const DRAFT_DETAIL = {
  workCaseId: 42,
  title: '주말 홀 서빙',
  workDate: '2026-08-01',
  startsAt: '2026-08-01T00:00:00Z',
  endsAt: '2026-08-01T09:00:00Z',
  breakMinutes: 60,
  breakPaid: false,
  dailyWage: 90000,
  status: 'DRAFT',
  termsVersion: 3,
  workplaceName: '강남점',
  worker: null,
  latestInvitation: null,
  contract: null,
  attendance: { checkedInAt: null, checkedOutAt: null },
  escrow: null,
  settlement: null
}

const PENDING_INVITATION = {
  status: 'PENDING',
  termsVersion: 3,
  expiresAt: '2026-08-01T00:00:00Z'
}

const PAYOUT_READY_DETAIL = {
  ...DRAFT_DETAIL,
  status: 'COMPLETED',
  worker: { workerId: 4, name: '이알바' },
  escrow: { status: 'HELD', amount: 90000 },
  attendance: {
    checkedInAt: '2026-08-01T00:00:00Z',
    checkedOutAt: '2026-08-01T09:00:00Z'
  },
  settlement: {
    status: 'SCHEDULED',
    amount: 90000,
    dueAt: '2026-08-02T09:00:00Z',
    completedAt: null
  }
}

const NO_SHOW_REFUND_READY_DETAIL = {
  ...DRAFT_DETAIL,
  status: 'NO_SHOW',
  worker: { workerId: 4, name: '이알바' },
  escrow: { status: 'HELD', amount: 90000 },
  settlement: { status: 'WAITING', amount: 90000, dueAt: null, completedAt: null }
}

const PAYOUT_RESULT = {
  settlementId: 1,
  status: 'COMPLETED',
  originalEscrowAmount: 90000,
  workerPaidAmount: 90000,
  ownerRefundAmount: 0,
  completedAt: '2026-08-13T01:00:00Z'
}

const REFUND_RESULT = {
  settlementId: 2,
  status: 'REFUNDED',
  originalEscrowAmount: 90000,
  workerPaidAmount: 0,
  ownerRefundAmount: 90000,
  completedAt: '2026-08-13T01:00:00Z'
}

function mountView() {
  return mount(OwnerWorkCaseDetailView, { global: { stubs: { teleport: true } } })
}

function toastMessages() {
  return useUiStore().toasts.map((t) => t.message)
}

describe('OwnerWorkCaseDetailView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    sessionStorage.clear()
    push.mockClear()
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-22T00:00:00Z'))
    getWorkCase.mockReset().mockResolvedValue({ ...DRAFT_DETAIL })
    listReports.mockReset().mockResolvedValue({
      content: [],
      page: { number: 0, size: 20, totalElements: 0, totalPages: 0 }
    })
    updateWorkCase.mockReset().mockResolvedValue(undefined)
    deleteWorkCase.mockReset().mockResolvedValue(undefined)
    createInvite.mockReset().mockResolvedValue({
      inviteUrl: 'https://app/invitations/abc',
      expiresAt: '2026-08-01T00:00:00Z'
    })
    reissueInvite.mockReset().mockResolvedValue({
      inviteUrl: 'https://app/invitations/new',
      expiresAt: '2026-08-01T00:00:00Z'
    })
    approveSettlement.mockReset().mockResolvedValue(PAYOUT_RESULT)
    approveNoShowRefund.mockReset().mockResolvedValue(REFUND_RESULT)
    newIdempotencyKey.mockClear()
    fetchWallet.mockReset().mockResolvedValue({
      currency: 'KRW',
      availableBalance: 100000,
      lockedBalance: 0
    })
    fetchTransactions.mockReset().mockResolvedValue({
      content: [],
      page: { number: 0, size: 20, totalElements: 0, totalPages: 0 }
    })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('근무 조건을 근무지 기준 시각으로 표시한다', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('09:00 ~ 18:00')
  })

  /*
   * termsVersion 은 조건 변경 감지·계약 Snapshot 추적용 내부 값이라 화면에 내보내지 않는다.
   * 픽스처의 termsVersion 은 3 이고 sourceTermsVersion 도 3 이므로, 어느 쪽이 되살아나도
   * 'v3' 또는 '조건 버전' 문구로 드러난다.
   */
  it('내부 값인 조건 버전을 화면에 노출하지 않는다', async () => {
    getWorkCase.mockResolvedValue({
      ...DRAFT_DETAIL,
      status: 'IN_PROGRESS',
      contract: {
        contractId: 7,
        documentId: 9,
        sourceTermsVersion: 3,
        acceptedAt: '2026-07-25T01:00:00Z'
      }
    })
    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).not.toContain('조건 버전')
    expect(text).not.toContain('v3')
    // 같은 영역의 다른 정보는 그대로 보여야 한다(행 전체를 지운 것이 아니다).
    expect(text).toContain('2026.07.25 10:00')
  })

  it('수정 실패의 breakMinutes 서버 오류를 휴게시간 필드에 표시한다', async () => {
    updateWorkCase.mockRejectedValue({
      response: {
        data: {
          fieldErrors: [{ field: 'breakMinutes', reason: '휴게시간은 0분 이상이어야 합니다.' }]
        }
      }
    })
    const wrapper = mountView()
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((button) => button.text() === '수정')
      .trigger('click')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(updateWorkCase).toHaveBeenCalled()
    expect(wrapper.text()).toContain('휴게시간은 0분 이상이어야 합니다.')
  })

  it('초대·계약·예치·근태 근거가 없으면 진행 현황을 감춘다', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.progress-section').exists()).toBe(false)
  })

  it('계약·예치·근태 근거를 진행 현황에 표시한다', async () => {
    getWorkCase.mockResolvedValue({
      ...DRAFT_DETAIL,
      status: 'IN_PROGRESS',
      worker: { workerId: 4, name: '이알바' },
      latestInvitation: { status: 'ACCEPTED', termsVersion: 3, expiresAt: null },
      contract: {
        contractId: 7,
        documentId: 9,
        sourceTermsVersion: 3,
        acceptedAt: '2026-07-25T01:00:00Z'
      },
      escrow: { status: 'HELD', amount: 90000 },
      attendance: { checkedInAt: '2026-08-01T00:05:00Z', checkedOutAt: null },
      settlement: { status: 'WAITING', amount: 90000, dueAt: null, completedAt: null }
    })
    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('수락됨')
    expect(text).toContain('2026.07.25 10:00') // 계약 확정(KST)
    expect(text).toContain('예치중')
    expect(text).toContain('09:05') // 출근(KST)
    expect(text).toContain('정산대기') // settlements.status=WAITING 이 한글로 매핑돼야 한다
    expect(text).not.toContain('WAITING')
    expect(wrapper.get('.contract-link').attributes('href')).toBe('/api/documents/9/file?mode=view')
  })

  it.each([
    ['WAITING', '정산대기'],
    ['SCHEDULED', '정산예정'],
    ['ON_HOLD', '정산보류'],
    ['PROCESSING', '정산중'],
    ['COMPLETED', '정산완료'],
    ['REFUNDED', '환불완료'],
    ['FAILED', '정산실패']
  ])('정산 상태 %s를 승인된 한글 문구로 표시한다', async (status, label) => {
    getWorkCase.mockResolvedValue({
      ...DRAFT_DETAIL,
      status: 'COMPLETED',
      escrow: { status: 'HELD', amount: 90000 },
      settlement: { status, amount: 90000, dueAt: null, completedAt: null }
    })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain(label)
    expect(wrapper.text()).not.toContain(status)
  })

  it('정상 지급을 확인한 뒤 상세·지갑·거래내역을 재조회한다', async () => {
    const completedDetail = {
      ...PAYOUT_READY_DETAIL,
      escrow: { status: 'RELEASED', amount: 90000 },
      settlement: {
        ...PAYOUT_READY_DETAIL.settlement,
        status: 'COMPLETED',
        completedAt: PAYOUT_RESULT.completedAt
      }
    }
    getWorkCase
      .mockReset()
      .mockResolvedValueOnce(PAYOUT_READY_DETAIL)
      .mockResolvedValue(completedDetail)
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('자동 지급 예정')
    expect(wrapper.text()).not.toContain('승인 만료')
    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('일급 전액 지급'))
      .trigger('click')

    expect(wrapper.text()).toContain('일급 전액을 지급할까요?')
    expect(wrapper.text()).toContain('90,000원')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '승인하기')
      .trigger('click')
    await flushPromises()

    expect(approveSettlement).toHaveBeenCalledWith(42, {
      idempotencyKey: 'settlement-intent-key'
    })
    expect(approveNoShowRefund).not.toHaveBeenCalled()
    expect(newIdempotencyKey).toHaveBeenCalledTimes(1)
    expect(getWorkCase).toHaveBeenCalledTimes(2)
    expect(fetchWallet).toHaveBeenCalledTimes(1)
    expect(fetchTransactions).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('정산완료')
    expect(toastMessages().join(' ')).toContain('90,000원이 알바생에게 지급됐어요')
  })

  it('NO_SHOW 환불을 지급과 다른 확인 문구로 승인하고 세 원천을 재조회한다', async () => {
    const refundedDetail = {
      ...NO_SHOW_REFUND_READY_DETAIL,
      escrow: { status: 'REFUNDED', amount: 90000 },
      settlement: {
        ...NO_SHOW_REFUND_READY_DETAIL.settlement,
        status: 'REFUNDED',
        completedAt: REFUND_RESULT.completedAt
      }
    }
    getWorkCase
      .mockReset()
      .mockResolvedValueOnce(NO_SHOW_REFUND_READY_DETAIL)
      .mockResolvedValue(refundedDetail)
    const wrapper = mountView()
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('노쇼 예치금 전액 환불'))
      .trigger('click')

    expect(wrapper.text()).toContain('노쇼 예치금을 환불할까요?')
    expect(wrapper.text()).toContain('알바생에게 지급하지 않고')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '승인하기')
      .trigger('click')
    await flushPromises()

    expect(approveNoShowRefund).toHaveBeenCalledWith(42, {
      idempotencyKey: 'settlement-intent-key'
    })
    expect(approveSettlement).not.toHaveBeenCalled()
    expect(getWorkCase).toHaveBeenCalledTimes(2)
    expect(fetchWallet).toHaveBeenCalledTimes(1)
    expect(fetchTransactions).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('환불완료')
    expect(toastMessages().join(' ')).toContain('90,000원이 사장님 지갑으로 반환됐어요')
  })

  it('CONFLICT 뒤 같은 지급 의도를 다시 확인할 때 멱등 Key를 바꾸지 않는다', async () => {
    const completedDetail = {
      ...PAYOUT_READY_DETAIL,
      escrow: { status: 'RELEASED', amount: 90000 },
      settlement: { ...PAYOUT_READY_DETAIL.settlement, status: 'COMPLETED' }
    }
    getWorkCase
      .mockReset()
      .mockResolvedValueOnce(PAYOUT_READY_DETAIL)
      .mockResolvedValueOnce(PAYOUT_READY_DETAIL)
      .mockResolvedValue(completedDetail)
    approveSettlement.mockRejectedValueOnce({ code: 'CONFLICT' }).mockResolvedValue(PAYOUT_RESULT)
    const wrapper = mountView()
    await flushPromises()

    const open = () =>
      wrapper
        .findAll('button')
        .find((button) => button.text().includes('일급 전액 지급'))
        .trigger('click')
    const confirm = () =>
      wrapper
        .findAll('button')
        .find((button) => button.text() === '승인하기')
        .trigger('click')

    await open()
    await confirm()
    await flushPromises()
    await open()
    await confirm()
    await flushPromises()

    expect(newIdempotencyKey).toHaveBeenCalledTimes(1)
    expect(approveSettlement).toHaveBeenNthCalledWith(1, 42, {
      idempotencyKey: 'settlement-intent-key'
    })
    expect(approveSettlement).toHaveBeenNthCalledWith(2, 42, {
      idempotencyKey: 'settlement-intent-key'
    })
  })

  it('응답을 확인하지 못한 뒤 화면에 재진입해도 같은 멱등 Key를 사용한다', async () => {
    getWorkCase.mockResolvedValue(PAYOUT_READY_DETAIL)
    approveSettlement
      .mockRejectedValueOnce(new Error('network'))
      .mockResolvedValueOnce(PAYOUT_RESULT)

    const firstWrapper = mountView()
    await flushPromises()
    await firstWrapper
      .findAll('button')
      .find((button) => button.text().includes('일급 전액 지급'))
      .trigger('click')
    await firstWrapper
      .findAll('button')
      .find((button) => button.text() === '승인하기')
      .trigger('click')
    await flushPromises()
    firstWrapper.unmount()

    const secondWrapper = mountView()
    await flushPromises()
    await secondWrapper
      .findAll('button')
      .find((button) => button.text().includes('일급 전액 지급'))
      .trigger('click')
    await secondWrapper
      .findAll('button')
      .find((button) => button.text() === '승인하기')
      .trigger('click')
    await flushPromises()

    expect(newIdempotencyKey).toHaveBeenCalledTimes(1)
    expect(approveSettlement).toHaveBeenNthCalledWith(1, 42, {
      idempotencyKey: 'settlement-intent-key'
    })
    expect(approveSettlement).toHaveBeenNthCalledWith(2, 42, {
      idempotencyKey: 'settlement-intent-key'
    })
  })

  it('느린 승인 응답 중 중복 클릭이 추가 지급 의도를 만들지 않는다', async () => {
    const completedDetail = {
      ...PAYOUT_READY_DETAIL,
      escrow: { status: 'RELEASED', amount: 90000 },
      settlement: { ...PAYOUT_READY_DETAIL.settlement, status: 'COMPLETED' }
    }
    getWorkCase
      .mockReset()
      .mockResolvedValueOnce(PAYOUT_READY_DETAIL)
      .mockResolvedValue(completedDetail)
    let resolveApproval
    approveSettlement.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveApproval = resolve
        })
    )
    const wrapper = mountView()
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('일급 전액 지급'))
      .trigger('click')
    const confirm = wrapper.findAll('button').find((button) => button.text() === '승인하기')
    await confirm.trigger('click')
    await confirm.trigger('click')

    expect(approveSettlement).toHaveBeenCalledTimes(1)
    expect(newIdempotencyKey).toHaveBeenCalledTimes(1)

    resolveApproval(PAYOUT_RESULT)
    await flushPromises()
  })

  it('이미 처리된 응답은 새 성공으로 간주하지 않고 세 원천을 재조회한다', async () => {
    const completedDetail = {
      ...PAYOUT_READY_DETAIL,
      escrow: { status: 'RELEASED', amount: 90000 },
      settlement: { ...PAYOUT_READY_DETAIL.settlement, status: 'COMPLETED' }
    }
    getWorkCase
      .mockReset()
      .mockResolvedValueOnce(PAYOUT_READY_DETAIL)
      .mockResolvedValue(completedDetail)
    approveSettlement.mockRejectedValue({ code: 'SETTLEMENT_ALREADY_PROCESSED' })
    const wrapper = mountView()
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('일급 전액 지급'))
      .trigger('click')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '승인하기')
      .trigger('click')
    await flushPromises()

    expect(getWorkCase).toHaveBeenCalledTimes(2)
    expect(fetchWallet).toHaveBeenCalledTimes(1)
    expect(fetchTransactions).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('정산완료')
    expect(toastMessages().join(' ')).toContain('이미 처리된 정산이에요')
    expect(toastMessages().join(' ')).not.toContain('지급을 승인했어요')
  })

  it('승인 후 재조회 일부가 실패하면 성공 상태를 추정하지 않고 재조회 버튼을 제공한다', async () => {
    getWorkCase.mockReset().mockResolvedValue(PAYOUT_READY_DETAIL)
    fetchTransactions.mockRejectedValueOnce(new Error('network'))
    const wrapper = mountView()
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('일급 전액 지급'))
      .trigger('click')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '승인하기')
      .trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('정산 정보 다시 불러오기')
    expect(toastMessages().join(' ')).toContain('최신 상태 일부를 불러오지 못했어요')
    expect(wrapper.text()).toContain('정산예정')
  })

  it('보존식이 맞지 않는 성공 응답은 완료로 안내하지 않고 같은 멱등 의도를 보존한다', async () => {
    getWorkCase.mockReset().mockResolvedValue(PAYOUT_READY_DETAIL)
    approveSettlement
      .mockResolvedValueOnce({
        ...PAYOUT_RESULT,
        workerPaidAmount: 80000,
        ownerRefundAmount: 0
      })
      .mockResolvedValueOnce(PAYOUT_RESULT)
    const wrapper = mountView()
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('일급 전액 지급'))
      .trigger('click')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '승인하기')
      .trigger('click')
    await flushPromises()

    expect(toastMessages().join(' ')).toContain(
      '상태와 금액이 일치하지 않아 완료로 표시하지 않았어요'
    )
    expect(toastMessages().join(' ')).not.toContain('알바생에게 지급됐어요')
    expect(wrapper.text()).toContain('정산예정')

    await wrapper
      .findAll('button')
      .find((button) => button.text().includes('일급 전액 지급'))
      .trigger('click')
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '승인하기')
      .trigger('click')
    await flushPromises()

    expect(newIdempotencyKey).toHaveBeenCalledTimes(1)
    expect(approveSettlement).toHaveBeenNthCalledWith(1, 42, {
      idempotencyKey: 'settlement-intent-key'
    })
    expect(approveSettlement).toHaveBeenNthCalledWith(2, 42, {
      idempotencyKey: 'settlement-intent-key'
    })
  })

  it('기한이 지난 PENDING 초대는 상태만이 아니라 기한 경과를 함께 알린다', async () => {
    vi.setSystemTime(new Date('2026-08-02T00:00:00Z'))
    getWorkCase.mockResolvedValue({ ...DRAFT_DETAIL, latestInvitation: PENDING_INVITATION })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('기한 지남')
  })

  it('유효한 초대가 있을 때만 새 링크로 교체를 제안한다', async () => {
    getWorkCase.mockResolvedValue({ ...DRAFT_DETAIL, latestInvitation: PENDING_INVITATION })
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('새 링크로 교체')
  })

  it('유효한 초대가 없으면 교체 버튼을 감춘다', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).not.toContain('새 링크로 교체')
  })

  it('교체를 확인하면 reissue 를 호출하고 이전 링크 무효를 알린다', async () => {
    getWorkCase.mockResolvedValue({ ...DRAFT_DETAIL, latestInvitation: PENDING_INVITATION })
    const wrapper = mountView()
    await flushPromises()

    const openButton = wrapper.findAll('button').find((b) => b.text().includes('새 링크로 교체'))
    await openButton.trigger('click')
    const confirm = wrapper.findAll('button').find((b) => b.text().includes('교체하기'))
    await confirm.trigger('click')
    await flushPromises()

    expect(reissueInvite).toHaveBeenCalledWith(42)
    expect(toastMessages().join(' ')).toContain('이전 링크는 더 이상 쓸 수 없어요')
  })

  it('발급 성공 시 만료 시각을 함께 안내한다', async () => {
    const wrapper = mountView()
    await flushPromises()

    const issueButton = wrapper.findAll('button').find((b) => b.text().includes('연결 링크 발급'))
    await issueButton.trigger('click')
    await flushPromises()

    expect(createInvite).toHaveBeenCalledWith(42)
    expect(toastMessages().join(' ')).toContain('2026.08.01 09:00까지 유효해요')
  })

  it('발급이 WORK_CASE_LOCKED 로 거절되면 사유를 구분해 안내한다', async () => {
    createInvite.mockRejectedValue({ code: 'WORK_CASE_LOCKED' })
    const wrapper = mountView()
    await flushPromises()

    const issueButton = wrapper.findAll('button').find((b) => b.text().includes('연결 링크 발급'))
    await issueButton.trigger('click')
    await flushPromises()

    expect(toastMessages().join(' ')).toContain('시작 시각이 지난 근무는 링크를 발급할 수 없어요')
  })

  it('초대 이력이 없으면 삭제로 안내한다', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('삭제'))
      .trigger('click')
    const confirm = wrapper.findAll('button.modal-btn, button').filter((b) => b.text() === '삭제')
    await confirm[confirm.length - 1].trigger('click')
    await flushPromises()

    expect(deleteWorkCase).toHaveBeenCalledWith(42)
    expect(toastMessages().join(' ')).toContain('근무를 삭제했어요')
  })

  // 서버는 초대 이력이 있으면 행을 지우지 않고 CANCELED 로 전이한다. 두 경로 모두 204 다.
  it('초대 이력이 있으면 취소 처리로 안내한다', async () => {
    getWorkCase.mockResolvedValue({
      ...DRAFT_DETAIL,
      latestInvitation: { status: 'REVOKED', termsVersion: 3, expiresAt: null }
    })
    const wrapper = mountView()
    await flushPromises()

    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('삭제'))
      .trigger('click')
    const confirm = wrapper.findAll('button').filter((b) => b.text() === '삭제')
    await confirm[confirm.length - 1].trigger('click')
    await flushPromises()

    expect(toastMessages().join(' ')).toContain('취소 처리했어요')
  })

  it('OWNER도 WORKER와 같은 DEMO 분쟁 결과를 확인한다', async () => {
    getWorkCase.mockResolvedValueOnce(PAYOUT_READY_DETAIL)
    listReports.mockResolvedValueOnce({
      content: [
        {
          reportId: 9,
          title: '임금 확인',
          content: '약정 일급 지급 여부를 확인해주세요.',
          status: 'RESOLVED',
          resolution: '기존 정산 흐름을 재개합니다.',
          requesterRole: 'WORKER',
          createdAt: '2026-08-15T01:00:00Z',
          resolvedAt: '2026-08-15T01:00:02Z',
          demoReview: {
            source: 'SIMULATED_LLM',
            decision: 'RESOLVE',
            reasonCodes: ['AGREED_WAGE_UNPAID'],
            summary: '약정 일급의 지급 여부를 확인했습니다.',
            confidence: 0.91,
            reviewedAt: '2026-08-15T01:00:02Z'
          }
        }
      ],
      page: { number: 0, size: 20, totalElements: 1, totalPages: 1 }
    })

    const wrapper = mountView()
    await flushPromises()

    expect(listReports).toHaveBeenCalledWith('42')
    expect(wrapper.text()).toContain('AI DEMO')
    expect(wrapper.text()).toContain('약정 일급의 지급 여부를 확인했습니다.')
    expect(wrapper.text()).toContain('AGREED_WAGE_UNPAID')
  })
})
