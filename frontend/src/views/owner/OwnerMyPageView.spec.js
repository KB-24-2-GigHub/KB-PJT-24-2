/**
 * 사장 마이페이지 프로필 카드 렌더링 계약 테스트.
 * getMe·getBadge 는 서로 다른 Endpoint 다. onMounted 에서 Promise.all 로 묶으면 badge 하나의
 * 실패가 me 까지 함께 날려 `v-if="me && badge"` 게이트를 절대 통과하지 못한다 — 과거 회귀다.
 *
 * 뱃지 값의 파생 규칙 자체는 `useTrustBadge.spec.js` 가 지킨다. 여기서는 상태별로 화면에
 * 무엇이 나오는지, 그리고 폐기된 "최근 15건" 기준이 다시 새지 않는지를 본다.
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

vi.mock('@/constants/pendingFeatures', () => ({
  PENDING_FEATURES: { PASSWORD_CHANGE: 187, WITHDRAWAL: 188 }
}))

import { PENDING_FEATURES } from '@/constants/pendingFeatures'
import { deleteMe, getBadge, getMe } from '@/services/users'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import OwnerMyPageView from '@/views/owner/OwnerMyPageView.vue'

const ME = { loginId: 'owner01', email: 'owner@test.com', name: '김사장', role: 'OWNER' }

/** SPEC-178-06 기준 2단계(누적 20건 이상·정상 비율 90% 이상) 응답. */
const BADGE = {
  badgeType: 'TRUST_OWNER',
  level: 2,
  recentCount: 22,
  normalCount: 21,
  remainingToNextLevel: 8,
  criterionLabel: '안심거래',
  criterionDesc:
    '누적 22건 중 정상 21건입니다. 다음 등급은 누적 30건 이상과 정상 비율 100% 이상이 필요하고, 건수는 8건 남았습니다.'
}

/** Teleport 를 stub 해 탈퇴 Modal 내용을 wrapper 안에서 찾을 수 있게 한다. */
function mountView() {
  return mount(OwnerMyPageView, { global: { stubs: { teleport: true } } })
}

function findByText(wrapper, selector, text) {
  const found = wrapper.findAll(selector).find((el) => el.text().trim() === text)
  if (!found) throw new Error(`'${text}' ${selector} 를 찾지 못했습니다`)
  return found
}

describe('OwnerMyPageView', () => {
  let logout
  let toastSpy

  beforeEach(() => {
    setActivePinia(createPinia())
    push.mockClear()
    getMe.mockReset()
    getBadge.mockReset()
    deleteMe.mockReset()
    PENDING_FEATURES.WITHDRAWAL = 188 // #188 준비 중(기본값) — 개별 테스트가 필요하면 override
    logout = vi.spyOn(useAuthStore(), 'logout').mockResolvedValue()
    toastSpy = vi.spyOn(useUiStore(), 'toast')
  })

  it('뱃지 조회가 실패해도 프로필 카드는 뱃지 없이 그대로 보여준다', async () => {
    getMe.mockResolvedValue({ ...ME })
    getBadge.mockRejectedValue({ response: { status: 500 } })

    const wrapper = mount(OwnerMyPageView)
    await flushPromises()

    expect(wrapper.find('.profile-card').exists()).toBe(true)
    expect(wrapper.text()).toContain('김사장')
    expect(wrapper.find('.badge-slot').exists()).toBe(false)
    expect(wrapper.find('.badge-notice').text()).toBe('뱃지 정보를 불러오지 못했어요.')
  })

  it('두 요청이 모두 성공하면 뱃지도 함께 보여준다', async () => {
    getMe.mockResolvedValue({ ...ME })
    getBadge.mockResolvedValue({ ...BADGE })

    const wrapper = mount(OwnerMyPageView)
    await flushPromises()

    expect(wrapper.find('.badge-slot').exists()).toBe(true)
    expect(wrapper.text()).toContain('안심거래')
  })

  describe('뱃지 표시 계약', () => {
    beforeEach(() => {
      getMe.mockResolvedValue({ ...ME })
    })

    it('폐기된 "최근 15건" 기준을 문구에도 진행률에도 쓰지 않는다', async () => {
      getBadge.mockResolvedValue({ ...BADGE })

      const wrapper = mount(OwnerMyPageView)
      await flushPromises()

      expect(wrapper.text()).not.toContain('최근 15건')
      // 진행률 분모는 서버가 준 recentCount + remainingToNextLevel = 30 이다.
      const bar = wrapper.find('.bar')
      expect(bar.attributes('aria-valuenow')).toBe('73')
      // 이름이 없으면 스크린리더가 맥락 없는 숫자만 읽는다.
      expect(bar.attributes('aria-label')).toBe('다음 등급까지 진행률')
    })

    it('정상 건수를 구조화된 문구로 보여준다', async () => {
      getBadge.mockResolvedValue({ ...BADGE })

      const wrapper = mount(OwnerMyPageView)
      await flushPromises()

      // normalPercent = round(21/22*100) = 95
      expect(wrapper.find('.badge-counts').text()).toBe('누적 정산 22건 중 안심정산 21건 (95%)')
    })

    // FE 정의문(*안심정산이란?…)은 카드가 아니라 레벨 설명 모달(TrustBadgeLevelModal) 맨 위에
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

      const wrapper = mount(OwnerMyPageView)
      await flushPromises()

      expect(wrapper.find('.badge-slot').exists()).toBe(true)
      expect(wrapper.find('.badge-notice').exists()).toBe(false)
      // 다음 등급(Lv.1) 문턱은 누적 10건·정상 비율 80%다.
      expect(wrapper.find('.level-remaining').text()).toBe(
        '다음 Lv.1까지 정산 10건, 안심거래 80%이상 유지 필요'
      )
      expect(wrapper.find('.bar').attributes('aria-valuenow')).toBe('0')
    })

    it('3단계는 남은 건수 문장 대신 최고 등급을 보여준다', async () => {
      getBadge.mockResolvedValue({
        ...BADGE,
        level: 3,
        recentCount: 30,
        normalCount: 30,
        remainingToNextLevel: 0,
        criterionDesc: '누적 30건 중 정상 30건으로 최고 등급입니다.'
      })

      const wrapper = mount(OwnerMyPageView)
      await flushPromises()

      expect(wrapper.find('.level-remaining').text()).toBe('최고 등급이에요.')
      expect(wrapper.text()).not.toContain('건 남음')
    })

    it('건수를 채웠지만 비율이 부족하면 승급 임박으로 읽히지 않게 구분한다', async () => {
      getBadge.mockResolvedValue({ ...BADGE, level: 2, recentCount: 33, remainingToNextLevel: 0 })

      const wrapper = mount(OwnerMyPageView)
      await flushPromises()

      const remaining = wrapper.find('.level-remaining').text()
      expect(remaining).toContain('건수 조건은 채웠어요')
      expect(remaining).toContain('정상 비율이 더 필요해요')
      expect(remaining).not.toContain('0건 남음')
      expect(remaining).not.toContain('최고 등급')
      // 가득 찬 바가 이 문구와 모순되고 스크린리더에는 "100 퍼센트"로 읽힌다.
      expect(wrapper.find('.bar').exists()).toBe(false)
    })

    /*
     * LOADING 분기는 flushPromises 뒤에는 절대 렌더되지 않는다. 해소되지 않은 promise 로
     * 잡아 두지 않으면 이 블록을 통째로 지워도 테스트가 전부 통과하고, 지우면 v-else 가
     * LOADING 을 받아 매 로드마다 오류 문구가 깜빡인다.
     */
    it('응답 전에는 오류가 아니라 로딩 안내를 보여준다', async () => {
      let resolveBadge
      getBadge.mockReturnValue(
        new Promise((resolve) => {
          resolveBadge = resolve
        })
      )

      const wrapper = mount(OwnerMyPageView)
      await flushPromises()

      expect(wrapper.find('.badge-notice').text()).toBe('뱃지 정보를 불러오는 중이에요…')
      expect(wrapper.find('.badge-slot').exists()).toBe(false)

      resolveBadge({ ...BADGE })
      await flushPromises()

      expect(wrapper.find('.badge-notice').exists()).toBe(false)
      expect(wrapper.find('.badge-slot').exists()).toBe(true)
    })

    /*
     * 이 통계는 승인 Endpoint 가 없어 ref(0) 자리표시자였다. 뱃지가 실데이터가 된 뒤로는
     * 같은 카드 안의 "정상 정산 0%" 가 자리표시자가 아니라 진짜 실적으로 읽힌다.
     */
    it('승인 Endpoint 가 없는 통계를 0으로 지어내 보여주지 않는다', async () => {
      getBadge.mockResolvedValue({ ...BADGE })

      const wrapper = mount(OwnerMyPageView)
      await flushPromises()

      // 문자열이 아니라 요소로 단언한다 — '정상 정산'·'신고' 는 뱃지 정의문
      // ('*안심거래란? 임금분쟁 신고 없이 정상 정산 완료')에도 정당하게 들어 있다.
      expect(wrapper.find('.stats-row').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('최근 구인')
    })

    it('403 은 일반 실패와 다른 문구로 구분한다', async () => {
      getBadge.mockRejectedValue({ response: { status: 403 } })

      const wrapper = mount(OwnerMyPageView)
      await flushPromises()

      expect(wrapper.find('.badge-notice').text()).toBe('뱃지를 볼 권한이 없어요.')
    })

    it('빈 응답은 실패와 다른 문구로 구분한다', async () => {
      getBadge.mockResolvedValue(null)

      const wrapper = mount(OwnerMyPageView)
      await flushPromises()

      expect(wrapper.find('.badge-notice').text()).toBe('뱃지 정보를 표시할 수 없어요.')
    })

    it('TRUST_WORKER 응답이 오면 사장 뱃지로 그리지 않는다', async () => {
      // 역할 불일치는 개발 중 원인을 남긴다 — 여기서는 출력만 가로챈다.
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
      getBadge.mockResolvedValue({
        ...BADGE,
        badgeType: 'TRUST_WORKER',
        criterionLabel: '성실근로'
      })

      const wrapper = mount(OwnerMyPageView)
      await flushPromises()
      warn.mockRestore()

      expect(wrapper.find('.badge-slot').exists()).toBe(false)
      expect(wrapper.text()).not.toContain('성실근로')
      expect(wrapper.find('.badge-notice').text()).toBe('뱃지 정보를 표시할 수 없어요.')
    })

    it('화면을 다시 열 때마다 뱃지를 다시 조회한다', async () => {
      getBadge.mockResolvedValue({ ...BADGE })

      mount(OwnerMyPageView)
      await flushPromises()
      mount(OwnerMyPageView)
      await flushPromises()

      expect(getBadge).toHaveBeenCalledTimes(2)
    })
  })

  it('내 정보 조회가 실패하면 프로필 카드를 보여주지 않는다', async () => {
    getMe.mockRejectedValue(new Error('Request failed with status code 401'))
    getBadge.mockResolvedValue({ ...BADGE })

    const wrapper = mount(OwnerMyPageView)
    await flushPromises()

    expect(wrapper.find('.profile-card').exists()).toBe(false)
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

  describe('회원 탈퇴 오류 귀속', () => {
    // #188 이 준비 중인 동안 탈퇴하기 버튼은 기본적으로 비활성화된다(아래 '준비 중 안내' 참고).
    // 이 블록은 오류 귀속 로직 자체(#188 해제 이후에도 지켜야 하는 계약)를 검증하므로
    // 버튼을 눌러 확인할 수 있도록 매 테스트마다 '해제된 상태'를 시뮬레이션한다.
    beforeEach(() => {
      PENDING_FEATURES.WITHDRAWAL = undefined
    })

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

  describe('회원 탈퇴 준비 중 안내', () => {
    // 이 블록은 #188 이 열려 있는 '오늘'의 상태(비활성화)를 고정한다.
    // #188 이 머지되면 PENDING_FEATURES.WITHDRAWAL 항목이 사라지므로 이 describe 는
    // 통과하지 못하게 된다 — mock 값을 다시 맞춰 억지로 살리지 말고 블록 전체를 삭제한다.
    it('모달에 준비 중 안내를 보여주고 탈퇴하기 버튼을 비활성화한다', async () => {
      const wrapper = mountView()
      await flushPromises()

      await findByText(wrapper, 'button', '회원 탈퇴').trigger('click')
      await flushPromises()

      expect(wrapper.text()).toContain('회원 탈퇴는 준비 중입니다')
      expect(findByText(wrapper, 'button', '탈퇴하기').attributes('disabled')).toBeDefined()
    })
  })
})
