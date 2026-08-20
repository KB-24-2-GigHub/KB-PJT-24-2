/**
 * 알바생 마이페이지 프로필 카드 렌더링 계약 테스트.
 * getMe·getBadge 는 서로 다른 Endpoint 다. onMounted 에서 Promise.all 로 묶으면 badge 하나의
 * 실패가 me 까지 함께 날려 `v-if="me && badge"` 게이트를 절대 통과하지 못한다 — 과거 회귀다.
 *
 * 뱃지 값의 파생 규칙 자체는 `useTrustBadge.spec.js` 가 지킨다. 여기서는 상태별 표시와,
 * 이 화면이 OWNER 산정치를 성실근로 뱃지로 그리지 않는지를 본다(#184 이전 Mock 의 실제 증상).
 */
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const push = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
  RouterLink: { props: ['to'], template: '<a><slot /></a>' }
}))

vi.mock('@/services/users', () => ({
  getMe: vi.fn(),
  getBadge: vi.fn(),
  deleteMe: vi.fn()
}))

import { deleteMe, getBadge, getMe } from '@/services/users'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import WorkerMyPageView from '@/views/worker/WorkerMyPageView.vue'

const ME = { loginId: 'worker01', email: 'worker@test.com', name: '이알바', role: 'WORKER' }
/** SPEC-178-06 기준 1단계(누적 10건 이상·정상 비율 80% 이상) 응답. */
const BADGE = {
  badgeType: 'TRUST_WORKER',
  level: 1,
  recentCount: 12,
  normalCount: 11,
  remainingToNextLevel: 8,
  criterionLabel: '성실근로',
  criterionDesc:
    '누적 12건 중 정상 11건입니다. 다음 등급은 누적 20건 이상과 정상 비율 90% 이상이 필요하고, 건수는 8건 남았습니다.'
}

/** Teleport 를 stub 해 탈퇴 Modal 내용을 wrapper 안에서 찾을 수 있게 한다. */
function mountView() {
  return mount(WorkerMyPageView, { global: { stubs: { teleport: true } } })
}

function findByText(wrapper, selector, text) {
  const found = wrapper.findAll(selector).find((el) => el.text().trim() === text)
  if (!found) throw new Error(`'${text}' ${selector} 를 찾지 못했습니다`)
  return found
}

describe('WorkerMyPageView', () => {
  let logout
  let clearSession
  let toastSpy

  beforeEach(() => {
    setActivePinia(createPinia())
    push.mockClear()
    getMe.mockReset()
    getBadge.mockReset()
    deleteMe.mockReset()
    logout = vi.spyOn(useAuthStore(), 'logout').mockResolvedValue()
    clearSession = vi.spyOn(useAuthStore(), 'clearSession').mockResolvedValue()
    toastSpy = vi.spyOn(useUiStore(), 'toast')
  })

  it('뱃지 조회가 실패해도 프로필 카드는 뱃지 없이 그대로 보여준다', async () => {
    getMe.mockResolvedValue({ ...ME })
    getBadge.mockRejectedValue({ response: { status: 500 } })

    const wrapper = mount(WorkerMyPageView)
    await flushPromises()

    expect(wrapper.find('.profile-card').exists()).toBe(true)
    expect(wrapper.text()).toContain('이알바')
    expect(wrapper.find('.badge-slot').exists()).toBe(false)
    expect(wrapper.find('.badge-notice').text()).toBe('뱃지 정보를 불러오지 못했어요.')
  })

  it('두 요청이 모두 성공하면 뱃지도 함께 보여준다', async () => {
    getMe.mockResolvedValue({ ...ME })
    getBadge.mockResolvedValue({ ...BADGE })

    const wrapper = mount(WorkerMyPageView)
    await flushPromises()

    expect(wrapper.find('.badge-slot').exists()).toBe(true)
    expect(wrapper.text()).toContain('성실근로')
  })

  describe('뱃지 표시 계약', () => {
    beforeEach(() => {
      getMe.mockResolvedValue({ ...ME })
    })

    /**
     * #184 이전의 Mock 은 역할과 무관하게 TRUST_OWNER 를 돌려줬고, 이 화면은 role="worker" 를
     * 하드코딩해 그 등급을 성실근로 뱃지로 그렸다. 응답 badgeType 을 보게 만든 이유다.
     */
    it('TRUST_OWNER 응답이 오면 성실근로 뱃지로 그리지 않는다', async () => {
      // 역할 불일치는 개발 중 원인을 남긴다 — 여기서는 출력만 가로챈다.
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
      getBadge.mockResolvedValue({
        ...BADGE,
        badgeType: 'TRUST_OWNER',
        level: 3,
        criterionLabel: '안심거래'
      })

      const wrapper = mount(WorkerMyPageView)
      await flushPromises()
      warn.mockRestore()

      expect(wrapper.find('.badge-slot').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('안심거래')
      expect(wrapper.find('.badge-notice').text()).toBe('뱃지 정보를 표시할 수 없어요.')
    })

    it('폐기된 "최근 15건" 기준을 문구에도 진행률에도 쓰지 않는다', async () => {
      getBadge.mockResolvedValue({ ...BADGE })

      const wrapper = mount(WorkerMyPageView)
      await flushPromises()

      expect(wrapper.text()).not.toContain('최근 15건')
      // 진행률 분모는 서버가 준 recentCount + remainingToNextLevel = 20 이다.
      expect(wrapper.find('.bar').attributes('aria-valuenow')).toBe('60')
    })

    it('정상 건수를 구조화된 문구로 보여준다', async () => {
      getBadge.mockResolvedValue({ ...BADGE })

      const wrapper = mount(WorkerMyPageView)
      await flushPromises()

      // normalPercent = round(11/12*100) = 92
      expect(wrapper.find('.badge-counts').text()).toBe('누적 근로 12건 중 성실근로 11건 (92%)')
    })

    // FE 정의문(*성실근로란?…)은 카드가 아니라 레벨 설명 모달(TrustBadgeLevelModal) 맨 위에
    // 있다 — 그 계약은 TrustBadgeLevelModal.spec.js 가 지킨다.

    it('미부여(0단계)는 오류가 아니라 남은 건수를 안내한다', async () => {
      getBadge.mockResolvedValue({
        ...BADGE,
        level: 0,
        recentCount: 0,
        normalCount: 0,
        remainingToNextLevel: 10,
        criterionDesc: '누적 0건 중 정상 0건입니다.'
      })

      const wrapper = mount(WorkerMyPageView)
      await flushPromises()

      expect(wrapper.find('.badge-slot').exists()).toBe(true)
      expect(wrapper.find('.badge-notice').exists()).toBe(false)
      // 다음 등급(Lv.1) 문턱은 누적 10건·정상 비율 80%다.
      expect(wrapper.find('.level-remaining').text()).toBe(
        '다음 Lv.1까지 근무 10건, 성실근로 80%이상 유지 필요'
      )
    })

    /*
     * 로딩·최고 등급·비율 부족·403·빈 응답의 상태 체인은 두 화면이 공유하는
     * TrustBadgeCard 가 소유하므로 OwnerMyPageView.spec.js 에서 한 번만 고정한다.
     * 여기서는 이 화면에만 있는 역할 결합과 WORKER 문구를 본다.
     */
    it('화면을 다시 열 때마다 뱃지를 다시 조회한다', async () => {
      getBadge.mockResolvedValue({ ...BADGE })

      mount(WorkerMyPageView)
      await flushPromises()
      mount(WorkerMyPageView)
      await flushPromises()

      expect(getBadge).toHaveBeenCalledTimes(2)
    })
  })

  it('로그아웃을 누르면 세션을 정리하고 온보딩으로 이동한다', async () => {
    const wrapper = mountView()
    await flushPromises()

    await findByText(wrapper, 'button', '로그아웃').trigger('click')
    await flushPromises()

    expect(logout).toHaveBeenCalledTimes(1)
    expect(push).toHaveBeenCalledWith('/')
  })

  it('로그아웃 서버 호출이 실패해도 온보딩으로 이동하고 실패를 알린다', async () => {
    // authStore.logout() 은 실패해도 로컬 상태를 이미 비운 뒤라 화면에 남으면 상태와 어긋난다.
    logout.mockRejectedValue({ response: { data: { message: '로그아웃에 실패했습니다.' } } })
    const wrapper = mountView()
    await flushPromises()

    await findByText(wrapper, 'button', '로그아웃').trigger('click')
    await flushPromises()

    expect(push).toHaveBeenCalledWith('/')
    expect(toastSpy).toHaveBeenCalledWith(
      '로그아웃에 실패했습니다.',
      expect.objectContaining({ type: 'danger' })
    )
  })

  it('회원 탈퇴 링크는 그대로 동작한다', async () => {
    const wrapper = mountView()
    await flushPromises()

    await findByText(wrapper, 'button', '회원 탈퇴').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('탈퇴하면 되돌릴 수 없어요')
  })

  it('로그아웃 요청 중에는 버튼이 비활성화되고 완료되면 다시 활성화된다', async () => {
    let resolveLogout
    logout.mockReturnValue(
      new Promise((resolve) => {
        resolveLogout = resolve
      })
    )
    const wrapper = mountView()
    await flushPromises()

    await findByText(wrapper, 'button', '로그아웃').trigger('click')
    await flushPromises()

    expect(findByText(wrapper, 'button', '로그아웃').attributes('disabled')).toBeDefined()

    resolveLogout()
    await flushPromises()

    expect(findByText(wrapper, 'button', '로그아웃').attributes('disabled')).toBeUndefined()
  })

  it('로그아웃이 실패해도 버튼은 다시 활성화된다', async () => {
    let rejectLogout
    logout.mockReturnValue(
      new Promise((_resolve, reject) => {
        rejectLogout = reject
      })
    )
    const wrapper = mountView()
    await flushPromises()

    await findByText(wrapper, 'button', '로그아웃').trigger('click')
    await flushPromises()

    expect(findByText(wrapper, 'button', '로그아웃').attributes('disabled')).toBeDefined()

    rejectLogout({ response: { data: { message: '로그아웃에 실패했습니다.' } } })
    await flushPromises()

    expect(findByText(wrapper, 'button', '로그아웃').attributes('disabled')).toBeUndefined()
  })

  describe('회원 탈퇴 성공', () => {
    async function withdraw(wrapper) {
      await findByText(wrapper, 'button', '회원 탈퇴').trigger('click')
      await flushPromises()
      await wrapper.find('input[type="password"]').setValue('current-pw1')
      await findByText(wrapper, 'button', '탈퇴하기').trigger('click')
      await flushPromises()
    }

    // 서버가 탈퇴 응답에서 Session 과 CSRF Token 을 이미 정리한다. 여기서 logout() 을 부르면
    // Token 없는 상태변경 요청이라 403 이 돌아오고, 성공 알림 뒤에 권한 오류가 겹쳐 뜬다.
    it('로그아웃 API 를 다시 부르지 않고 로컬 상태만 정리한다', async () => {
      deleteMe.mockResolvedValue(undefined)
      const wrapper = mountView()
      await flushPromises()

      await withdraw(wrapper)

      expect(clearSession).toHaveBeenCalledTimes(1)
      expect(logout).not.toHaveBeenCalled()
      expect(push).toHaveBeenCalledWith('/')
    })

    it('성공 알림 하나만 띄우고 오류 알림을 겹쳐 띄우지 않는다', async () => {
      deleteMe.mockResolvedValue(undefined)
      const wrapper = mountView()
      await flushPromises()

      await withdraw(wrapper)

      expect(toastSpy).toHaveBeenCalledWith('회원 탈퇴가 완료됐어요.', { type: 'success' })
      expect(toastSpy).not.toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({ type: 'danger' })
      )
    })
  })

  describe('회원 탈퇴 오류 귀속', () => {
    async function openModalAndFillPassword(wrapper, password = 'current-pw1') {
      await findByText(wrapper, 'button', '회원 탈퇴').trigger('click')
      await flushPromises()
      await wrapper.find('input[type="password"]').setValue(password)
    }

    it('필드를 지목하지 않은 실패는 비밀번호 오류로 표시하지 않는다', async () => {
      deleteMe.mockRejectedValue({ response: { status: 500, data: {} } })

      const wrapper = mountView()
      await flushPromises()
      await openModalAndFillPassword(wrapper)
      await findByText(wrapper, 'button', '탈퇴하기').trigger('click')
      await flushPromises()

      expect(wrapper.find('.msg.error').exists()).toBe(false)
      expect(toastSpy).toHaveBeenCalledWith('탈퇴 처리 중 오류가 발생했어요.', { type: 'danger' })
    })

    it('서버가 비밀번호 필드를 지목하면 그 필드 아래에 표시한다', async () => {
      const fieldError = { field: 'password', reason: '비밀번호가 올바르지 않습니다.' }
      deleteMe.mockRejectedValue({
        response: { status: 400, data: { fieldErrors: [fieldError] } },
        fieldErrors: [fieldError]
      })

      const wrapper = mountView()
      await flushPromises()
      await openModalAndFillPassword(wrapper)
      await findByText(wrapper, 'button', '탈퇴하기').trigger('click')
      await flushPromises()

      expect(wrapper.find('.msg.error').text()).toBe('비밀번호가 올바르지 않습니다.')
    })

    it('fieldErrors 없이 message 만 있으면 필드가 아니라 폼 레벨 토스트로 보여준다', async () => {
      deleteMe.mockRejectedValue({
        response: { status: 409, data: { message: '진행 중인 근무가 있어 탈퇴할 수 없어요.' } }
      })

      const wrapper = mountView()
      await flushPromises()
      await openModalAndFillPassword(wrapper)
      await findByText(wrapper, 'button', '탈퇴하기').trigger('click')
      await flushPromises()

      expect(wrapper.find('.msg.error').exists()).toBe(false)
      expect(toastSpy).toHaveBeenCalledWith('진행 중인 근무가 있어 탈퇴할 수 없어요.', {
        type: 'danger'
      })
    })
  })

  // #188 로 POST /api/users/me/withdrawal 이 살아나 준비 중 게이트를 제거했다.
  // 게이트가 되돌아오면(탈퇴 버튼 비활성화·안내 문구) 이 테스트가 잡는다.
  describe('회원 탈퇴 게이트 해제', () => {
    it('준비 중 안내 없이 탈퇴할 수 있다', async () => {
      const wrapper = mountView()
      await flushPromises()

      await findByText(wrapper, 'button', '회원 탈퇴').trigger('click')
      await flushPromises()

      expect(wrapper.text()).not.toContain('회원 탈퇴는 준비 중입니다')
      expect(findByText(wrapper, 'button', '탈퇴하기').attributes('disabled')).toBeUndefined()
    })
  })
})
