import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import InviteConfirmView from '@/views/invite/InviteConfirmView.vue'

const push = vi.fn()
const replace = vi.fn()

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { token: 'safe_token' } }),
  useRouter: () => ({ push, replace })
}))
vi.mock('@/services/invites', () => ({ confirmInvite: vi.fn(), getInvite: vi.fn() }))
vi.mock('@/services/workCases', () => ({ getWorkCase: vi.fn() }))
vi.mock('@/services/wallet', () => ({ fetchWallet: vi.fn(), fetchTransactions: vi.fn() }))
vi.mock('@/services/documents', () => ({
  contractFileUrl: vi.fn(
    (documentId, mode = 'view') => `/api/documents/${documentId}/file?mode=${mode}`
  ),
  fetchDocumentFile: vi.fn()
}))
vi.mock('@/services/http', async (importOriginal) => {
  const actual = await importOriginal()
  return { ...actual, newIdempotencyKey: vi.fn(() => 'accept-intent-key') }
})

import { newIdempotencyKey } from '@/services/http'
import { fetchDocumentFile } from '@/services/documents'
import { confirmInvite, getInvite } from '@/services/invites'
import { fetchTransactions, fetchWallet } from '@/services/wallet'
import { getWorkCase } from '@/services/workCases'
import { useUiStore } from '@/stores/ui'

const INVITE = {
  title: '주말 홀 서빙',
  workplaceName: '강남점',
  startsAt: '2026-08-20T01:00:00Z',
  endsAt: '2026-08-20T09:00:00Z',
  breakMinutes: 60,
  breakPaid: false,
  dailyWage: 120000,
  termsVersion: 3,
  expiresAt: '2026-08-20T01:00:00Z',
  ownerBadge: null
}

const ACCEPTED_WORK_CASE = {
  workCaseId: 42,
  title: '주말 홀 서빙',
  workplaceName: '강남점',
  startsAt: '2026-08-20T01:00:00Z',
  endsAt: '2026-08-20T09:00:00Z',
  dailyWage: 120000,
  contract: { documentId: 99, sourceTermsVersion: 3, acceptedAt: '2026-08-10T04:00:00Z' }
}

function mountView() {
  return mount(InviteConfirmView)
}

function acceptButton(wrapper) {
  return wrapper
    .findAll('button')
    .find(
      (button) =>
        button.text().includes('근무 확정') || button.text().includes('같은 요청으로 다시 확인')
    )
}

describe('InviteConfirmView', () => {
  const createObjectURL = vi.fn()
  const revokeObjectURL = vi.fn()

  beforeEach(() => {
    setActivePinia(createPinia())
    push.mockReset()
    replace.mockReset()
    getInvite.mockReset().mockResolvedValue({ ...INVITE })
    confirmInvite.mockReset().mockResolvedValue({ workCaseId: 42, escrowStatus: 'HELD' })
    getWorkCase.mockReset().mockResolvedValue({ ...ACCEPTED_WORK_CASE })
    fetchWallet
      .mockReset()
      .mockResolvedValue({ currency: 'KRW', availableBalance: 0, lockedBalance: 0 })
    fetchTransactions.mockReset().mockResolvedValue({
      content: [],
      page: { number: 0, size: 20, totalElements: 0, totalPages: 0 }
    })
    newIdempotencyKey.mockClear()
    fetchDocumentFile.mockReset().mockResolvedValue(new Blob(['pdf'], { type: 'application/pdf' }))
    createObjectURL.mockReset().mockReturnValue('blob:https://gighub.store/contract-preview')
    revokeObjectURL.mockReset()
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL })
  })

  it('승인 DTO를 KST 시각으로 표시하고 Canvas를 만들지 않는다', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(getInvite).toHaveBeenCalledWith('safe_token')
    expect(wrapper.text()).toContain('2026.08.20')
    expect(wrapper.text()).toContain('10:00 ~ 18:00')
    expect(wrapper.text()).toContain('등록된 배지 없음')
    expect(wrapper.find('canvas').exists()).toBe(false)
  })

  /*
   * 초대의 OWNER 뱃지는 승인 Read Model(GET /api/invitations/{token})이 내려주는 값만 쓴다.
   * 0단계는 계약상 ownerBadge=null 이고, 1~3단계만 객체로 온다(SPEC-178-06).
   * 이 화면이 타인 ID 로 Badge Endpoint 를 따로 부르면 승인되지 않은 조회 경로가 된다.
   */
  describe('OWNER 뱃지', () => {
    it.each([1, 2, 3])('Read Model 이 준 %i단계를 그대로 그린다', async (level) => {
      getInvite.mockResolvedValue({
        ...INVITE,
        ownerBadge: { badgeType: 'TRUST_OWNER', level }
      })

      const wrapper = mountView()
      await flushPromises()

      expect(wrapper.find(`img[alt="owner 뱃지 ${level}단계"]`).exists()).toBe(true)
      expect(wrapper.text()).not.toContain('등록된 배지 없음')
    })

    it('뱃지를 그리려고 별도 사용자 조회를 하지 않는다', async () => {
      getInvite.mockResolvedValue({
        ...INVITE,
        ownerBadge: { badgeType: 'TRUST_OWNER', level: 2 }
      })

      const wrapper = mountView()
      await flushPromises()

      expect(wrapper.find('img[alt="owner 뱃지 2단계"]').exists()).toBe(true)
      expect(getInvite).toHaveBeenCalledTimes(1)
    })
  })

  /*
   * termsVersion 은 서버가 조건 변경을 감지하는 내부 값이라 화면에 내보내지 않는다.
   * 수락 직전 화면이라 의미 없는 값이 오히려 오해를 준다. 픽스처의 termsVersion 은 3 이다.
   */
  it('내부 값인 조건 버전을 화면에 노출하지 않는다', async () => {
    const wrapper = mountView()
    await flushPromises()

    const text = wrapper.text()
    expect(text).not.toContain('조건 버전')
    expect(text).not.toContain('v3')
    // 만료 시각은 같은 목록에 남아 있어야 한다(행 하나만 제거한 것이다).
    expect(text).toContain('초대 만료')
  })

  it('Body 없는 수락 후 근무·지갑을 재조회하고 계약 최종본 Blob을 연다', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('input[type="checkbox"]').setValue(true)
    await acceptButton(wrapper).trigger('click')
    await flushPromises()

    expect(confirmInvite).toHaveBeenCalledWith('safe_token', {
      idempotencyKey: 'accept-intent-key'
    })
    expect(getWorkCase).toHaveBeenCalledWith(42)
    expect(fetchWallet).toHaveBeenCalled()
    expect(fetchTransactions).toHaveBeenCalled()
    expect(fetchDocumentFile).toHaveBeenCalledWith(99, 'view')
    expect(wrapper.get('iframe').attributes('src')).toBe(
      'blob:https://gighub.store/contract-preview'
    )
    expect(wrapper.get('.download-link').attributes('href')).toBe(
      '/api/documents/99/file?mode=download'
    )
    expect(wrapper.text()).toContain('임금 예치 완료')
  })

  /*
   * 수락 시 서버가 일급을 에스크로로 잡으므로(escrowStatus=HELD) 확정 직후 알바생이 가장
   * 확인하고 싶은 것은 임금이 예치됐다는 사실이다. 안심지갑이 주 경로이고 근무 상세는 보조다.
   */
  it('확정 후 안심지갑과 근무 상세 두 경로를 모두 제공한다', async () => {
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('input[type="checkbox"]').setValue(true)
    await acceptButton(wrapper).trigger('click')
    await flushPromises()

    const wallet = wrapper.findAll('button').find((b) => b.text().includes('안심지갑'))
    await wallet.trigger('click')
    expect(push).toHaveBeenCalledWith('/worker/home')

    const detail = wrapper.findAll('button').find((b) => b.text().includes('근무 상세'))
    await detail.trigger('click')
    expect(push).toHaveBeenCalledWith('/worker/work/work-cases/42')
  })

  /*
   * 근무는 이미 확정된 상태다. 동기화 실패를 이유로 이동을 막으면 사용자가 화면에 갇혀
   * 같은 수락을 다시 시도하게 된다.
   */
  it('수락 후 동기화가 실패해도 이동 경로를 막지 않는다', async () => {
    getWorkCase.mockRejectedValue(new Error('network'))
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('input[type="checkbox"]').setValue(true)
    await acceptButton(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('최신 정보를 모두 불러오지 못했어요')

    await wrapper
      .findAll('button')
      .find((b) => b.text().includes('안심지갑'))
      .trigger('click')
    expect(push).toHaveBeenCalledWith('/worker/home')
  })

  it('결과가 불확실한 사용자 재확인은 같은 멱등 Key를 유지한다', async () => {
    confirmInvite
      .mockRejectedValueOnce(new Error('network'))
      .mockRejectedValueOnce({
        code: 'CONFLICT',
        response: { status: 409, data: { code: 'CONFLICT', message: '변경된 처리 중 문구' } }
      })
      .mockResolvedValueOnce({ workCaseId: 42, escrowStatus: 'HELD' })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('input[type="checkbox"]').setValue(true)
    await acceptButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('같은 요청으로 다시 확인')

    await acceptButton(wrapper).trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('같은 요청으로 다시 확인')

    await acceptButton(wrapper).trigger('click')
    await flushPromises()

    expect(newIdempotencyKey).toHaveBeenCalledTimes(1)
    expect(confirmInvite.mock.calls[0][1].idempotencyKey).toBe('accept-intent-key')
    expect(confirmInvite.mock.calls[1][1].idempotencyKey).toBe('accept-intent-key')
    expect(confirmInvite.mock.calls[2][1].idempotencyKey).toBe('accept-intent-key')
  })

  it('첫 CONFLICT는 서버 문구와 무관하게 종료하고 다음 클릭에서 Key를 회전한다', async () => {
    newIdempotencyKey
      .mockReturnValueOnce('first-accept-intent')
      .mockReturnValueOnce('second-accept-intent')
    confirmInvite
      .mockRejectedValueOnce({
        code: 'CONFLICT',
        response: { status: 409, data: { code: 'CONFLICT', message: '변경된 잔액 부족 문구' } }
      })
      .mockResolvedValueOnce({ workCaseId: 42, escrowStatus: 'HELD' })
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('input[type="checkbox"]').setValue(true)
    await acceptButton(wrapper).trigger('click')
    await flushPromises()
    await acceptButton(wrapper).trigger('click')
    await flushPromises()

    expect(newIdempotencyKey).toHaveBeenCalledTimes(2)
    expect(confirmInvite.mock.calls[0][1].idempotencyKey).toBe('first-accept-intent')
    expect(confirmInvite.mock.calls[1][1].idempotencyKey).toBe('second-accept-intent')
  })

  it('승인 오류 Code를 구분해 안내한다', async () => {
    getInvite.mockRejectedValue({
      code: 'INVITATION_REVOKED',
      response: { status: 409, data: { code: 'INVITATION_REVOKED' } }
    })

    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.text()).toContain('철회된 초대 링크')
  })

  it('OWNER·잘못된 역할 응답은 403 화면으로 보낸다', async () => {
    getInvite.mockRejectedValue({
      code: 'ROLE_MISMATCH',
      response: { status: 403, data: { code: 'ROLE_MISMATCH' } }
    })

    mountView()
    await flushPromises()

    expect(replace).toHaveBeenCalledWith('/forbidden')
  })

  it('수락 처리 중 중복 클릭은 같은 요청조차 다시 보내지 않는다', async () => {
    let resolveAcceptance
    confirmInvite.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveAcceptance = resolve
        })
    )
    const wrapper = mountView()
    await flushPromises()

    await wrapper.get('input[type="checkbox"]').setValue(true)
    const button = acceptButton(wrapper)
    await button.trigger('click')
    await button.trigger('click')

    expect(confirmInvite).toHaveBeenCalledTimes(1)
    resolveAcceptance({ workCaseId: 42, escrowStatus: 'HELD' })
    await flushPromises()
  })

  it('동의 없이 누르면 API를 호출하지 않는다', async () => {
    const wrapper = mountView()
    await flushPromises()

    await acceptButton(wrapper).trigger('click')

    expect(confirmInvite).not.toHaveBeenCalled()
    expect(useUiStore().toasts.at(-1).message).toContain('동의해주세요')
  })
})
