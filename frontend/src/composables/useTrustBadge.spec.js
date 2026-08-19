/**
 * 신뢰 뱃지 표시 계약 테스트 — SPEC-178-06 / GET /api/users/me/badge.
 *
 * 등급 문턱은 서버 소유이므로 여기서 검증하는 것은 "서버가 준 값을 화면 의미로 옮기는 규칙"이다.
 * 문턱 자체(누적 10/20/30, 정상 비율 80/90/100%)는 Backend `TrustBadgeCriteriaTest` 가 지킨다.
 * 경계 입력은 그 문턱에서 서버가 실제로 내려보내는 조합을 그대로 쓴다.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/users', () => ({ getBadge: vi.fn() }))

import { BADGE_STATE, useTrustBadge } from '@/composables/useTrustBadge'
import { getBadge } from '@/services/users'

/** 승인 응답 6필드를 모두 갖춘 기본형. 개별 테스트는 필요한 필드만 덮어쓴다. */
function response(overrides = {}) {
  return {
    badgeType: 'TRUST_WORKER',
    level: 1,
    recentCount: 12,
    normalCount: 11,
    remainingToNextLevel: 8,
    criterionLabel: '성실근로',
    criterionDesc: '누적 12건 중 정상 11건입니다.',
    ...overrides
  }
}

async function loadWorkerBadge(data) {
  getBadge.mockResolvedValue(data)
  const badge = useTrustBadge('worker')
  await badge.load()
  return badge
}

describe('useTrustBadge', () => {
  beforeEach(() => {
    getBadge.mockReset()
  })

  describe('등급 경계', () => {
    it('이력이 없으면 오류가 아니라 미부여(0단계)다', async () => {
      const badge = await loadWorkerBadge(
        response({ level: 0, recentCount: 0, remainingToNextLevel: 10 })
      )

      expect(badge.state.value).toBe(BADGE_STATE.READY)
      expect(badge.level.value).toBe(0)
      expect(badge.maxLevel.value).toBe(false)
      expect(badge.progressPercent.value).toBe(0)
      expect(badge.nextLevelLabel.value).toBe('Lv.1')
    })

    it('1단계 문턱 직전(누적 9건)은 아직 0단계이고 진행률만 오른다', async () => {
      const badge = await loadWorkerBadge(
        response({ level: 0, recentCount: 9, remainingToNextLevel: 1 })
      )

      expect(badge.level.value).toBe(0)
      expect(badge.progressPercent.value).toBe(90)
      expect(badge.nextLevelLabel.value).toBe('Lv.1')
    })

    it('누적 10건 1단계는 다음 문턱 20건을 기준으로 진행률을 보인다', async () => {
      const badge = await loadWorkerBadge(
        response({ level: 1, recentCount: 10, remainingToNextLevel: 10 })
      )

      expect(badge.progressPercent.value).toBe(50)
      expect(badge.nextLevelLabel.value).toBe('Lv.2')
      expect(badge.countMetRatioShort.value).toBe(false)
    })

    it('누적 20건 2단계는 다음 문턱 30건을 기준으로 진행률을 보인다', async () => {
      const badge = await loadWorkerBadge(
        response({ level: 2, recentCount: 20, remainingToNextLevel: 10 })
      )

      expect(badge.progressPercent.value).toBe(67)
      expect(badge.nextLevelLabel.value).toBe('Lv.3')
    })

    it('누적 30건 3단계는 최고 등급이고 다음 등급 라벨을 만들지 않는다', async () => {
      const badge = await loadWorkerBadge(
        response({ level: 3, recentCount: 30, remainingToNextLevel: 0 })
      )

      expect(badge.maxLevel.value).toBe(true)
      expect(badge.countMetRatioShort.value).toBe(false)
      expect(badge.progressPercent.value).toBe(100)
      expect(badge.showProgress.value).toBe(true)
      expect(badge.nextLevelLabel.value).toBe('최고 등급')
    })

    /**
     * 남은 건수 0 이 만드는 두 번째 경우다. SPEC-178-06 은 건수를 채웠어도 정상 비율이
     * 모자라면 등급을 올리지 않고 remainingToNextLevel 만 0으로 준다. 최고 등급과 같은
     * 문장으로 묶이면 "0건 남음"이 곧 승급으로 읽힌다.
     */
    it('건수는 채웠는데 정상 비율이 부족한 경우를 최고 등급과 구분한다', async () => {
      const badge = await loadWorkerBadge(
        response({ level: 1, recentCount: 25, remainingToNextLevel: 0 })
      )

      expect(badge.countMetRatioShort.value).toBe(true)
      expect(badge.maxLevel.value).toBe(false)
      expect(badge.nextLevelLabel.value).toBe('Lv.2')
    })

    /*
     * 남은 건수가 0이라 진행률은 100 이 되는데 등급은 오르지 않은 상태다. 가득 찬 바를
     * 그대로 두면 바로 아래 "정상 비율이 더 필요해요" 와 모순되고, 스크린리더는
     * aria-valuenow="100" 을 "100 퍼센트" 로 읽어 오해가 그대로 전달된다.
     */
    it('비율 부족 상태에서는 100% 진행바를 내보내지 않는다', async () => {
      const badge = await loadWorkerBadge(
        response({ level: 1, recentCount: 25, remainingToNextLevel: 0 })
      )

      expect(badge.progressPercent.value).toBe(100)
      expect(badge.showProgress.value).toBe(false)
    })
  })

  describe('정상 건수·비율(SPEC-432-01)', () => {
    it('정상 건수와 누적 건수를 서버 값 그대로 노출한다', async () => {
      const badge = await loadWorkerBadge(response({ recentCount: 12, normalCount: 11 }))

      expect(badge.totalCount.value).toBe(12)
      expect(badge.normalCount.value).toBe(11)
    })

    it('비율은 반올림한 표시 전용 값이다', async () => {
      const badge = await loadWorkerBadge(response({ recentCount: 3, normalCount: 2 }))

      expect(badge.normalPercent.value).toBe(67) // 2/3 = 66.67% → 반올림
    })

    it('누적 건수가 0이면 비율도 0이다', async () => {
      const badge = await loadWorkerBadge(
        response({ level: 0, recentCount: 0, normalCount: 0, remainingToNextLevel: 10 })
      )

      expect(badge.normalPercent.value).toBe(0)
    })

    it('다음 등급의 정상 비율 문턱을 알려준다', async () => {
      const badge = await loadWorkerBadge(response({ level: 1 }))

      expect(badge.nextThresholdPercent.value).toBe(90) // Lv.2 문턱
    })

    it('최고 등급에는 다음 문턱이 없다', async () => {
      const badge = await loadWorkerBadge(
        response({ level: 3, recentCount: 30, normalCount: 30, remainingToNextLevel: 0 })
      )

      expect(badge.nextThresholdPercent.value).toBeNull()
    })

    it('역할별 타이틀·라벨을 BADGE_TYPE 에서 그대로 가져온다', async () => {
      const badge = await loadWorkerBadge(response())

      expect(badge.title.value).toBe('성실알바')
      expect(badge.totalLabel.value).toBe('근로')
      expect(badge.normalLabel.value).toBe('성실근로')
      expect(badge.remainingLabel.value).toBe('근무')
    })
  })

  describe('역할 정합성', () => {
    let warn

    beforeEach(() => {
      // MISMATCH 는 개발 중 원인을 남긴다 — 테스트 출력이 지저분해지지 않게 가로챈다.
      warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    })

    afterEach(() => {
      warn.mockRestore()
    })

    it('역할 불일치는 개발 중 원인을 남긴다', async () => {
      await loadWorkerBadge(response({ badgeType: 'TRUST_OWNER' }))

      expect(warn).toHaveBeenCalledWith(expect.stringContaining('TRUST_OWNER'))
    })

    it('응답 badgeType 에서 역할을 파생한다', async () => {
      const badge = await loadWorkerBadge(response({ badgeType: 'TRUST_WORKER' }))

      expect(badge.role.value).toBe('worker')
      expect(badge.definitionTitle.value).toContain('성실근로란')
    })

    it('WORKER 화면이 TRUST_OWNER 응답을 받으면 그리지 않는다', async () => {
      const badge = await loadWorkerBadge(response({ badgeType: 'TRUST_OWNER' }))

      expect(badge.state.value).toBe(BADGE_STATE.MISMATCH)
      expect(badge.badge.value).toBeNull()
      expect(badge.role.value).toBeNull()
    })

    it('OWNER 화면이 TRUST_WORKER 응답을 받아도 그리지 않는다', async () => {
      getBadge.mockResolvedValue(response({ badgeType: 'TRUST_WORKER' }))
      const badge = useTrustBadge('owner')
      await badge.load()

      expect(badge.state.value).toBe(BADGE_STATE.MISMATCH)
    })
  })

  describe('빈 응답과 오류', () => {
    it.each([
      ['본문 없음', null],
      ['빈 객체', {}],
      ['알 수 없는 badgeType', response({ badgeType: 'TRUST_ADMIN' })],
      ['level 누락', response({ level: undefined })],
      ['등급 범위 초과', response({ level: 4 })],
      ['음수 누적 건수', response({ recentCount: -1 })],
      ['정상 건수 누락', response({ normalCount: undefined })],
      ['음수 정상 건수', response({ normalCount: -1 })],
      ['남은 건수 누락', response({ remainingToNextLevel: null })],
      ['빈 criterionLabel', response({ criterionLabel: '  ' })],
      // 객체 리터럴 상속 프로퍼티는 truthy 라, 브래킷 조회로 멤버십을 보면 통과해 버린다.
      ['Object.prototype 상속 이름', response({ badgeType: 'constructor' })],
      ['hasOwnProperty', response({ badgeType: 'hasOwnProperty' })]
    ])('%s 은 오류가 아니라 빈 응답으로 구분한다', async (_label, data) => {
      const badge = await loadWorkerBadge(data)

      expect(badge.state.value).toBe(BADGE_STATE.EMPTY)
      expect(badge.badge.value).toBeNull()
    })

    /*
     * `criterionDesc` 는 표시 의미를 결정하지 않는 자유 문장이다. 이게 비었다고 뱃지 전체를
     * 숨기면 등급이라는 더 중요한 정보까지 잃는다 — READY 로 두고 표시 쪽에서 흘려보낸다.
     */
    it('진행 설명문이 비어도 뱃지 자체는 보여준다', async () => {
      const badge = await loadWorkerBadge(response({ criterionDesc: '' }))

      expect(badge.state.value).toBe(BADGE_STATE.READY)
      expect(badge.level.value).toBe(1)
    })

    it('403 은 권한 오류로 구분한다', async () => {
      getBadge.mockRejectedValue({ response: { status: 403 } })
      const badge = useTrustBadge('worker')
      await badge.load()

      expect(badge.state.value).toBe(BADGE_STATE.FORBIDDEN)
    })

    it('그 밖의 실패는 일반 오류다', async () => {
      getBadge.mockRejectedValue({ response: { status: 500 } })
      const badge = useTrustBadge('worker')
      await badge.load()

      expect(badge.state.value).toBe(BADGE_STATE.ERROR)
    })

    it('401 은 인터셉터가 처리하므로 권한 오류 상태로 남기지 않는다', async () => {
      getBadge.mockRejectedValue({ response: { status: 401 } })
      const badge = useTrustBadge('worker')
      await badge.load()

      expect(badge.state.value).toBe(BADGE_STATE.ERROR)
    })

    it('조회 전 상태는 로딩이다', () => {
      const badge = useTrustBadge('worker')

      expect(badge.state.value).toBe(BADGE_STATE.LOADING)
      expect(badge.progressPercent.value).toBe(0)
    })
  })

  describe('캐시 없음과 갱신', () => {
    it('load 를 다시 부르면 캐시를 쓰지 않고 매번 서버에 묻는다', async () => {
      getBadge.mockResolvedValue(response())
      const badge = useTrustBadge('worker')

      await badge.load()
      await badge.load()

      expect(getBadge).toHaveBeenCalledTimes(2)
    })

    it('재조회가 실패하면 직전 뱃지를 남기지 않는다', async () => {
      const badge = useTrustBadge('worker')
      getBadge.mockResolvedValue(response({ level: 3, remainingToNextLevel: 0 }))
      await badge.load()
      expect(badge.level.value).toBe(3)

      getBadge.mockRejectedValue({ response: { status: 500 } })
      await badge.load()

      expect(badge.state.value).toBe(BADGE_STATE.ERROR)
      expect(badge.badge.value).toBeNull()
      expect(badge.level.value).toBe(0)
      expect(badge.maxLevel.value).toBe(false)
    })

    /*
     * 두 조회가 겹치면 늦게 도착한 이전 응답이 최신 결과를 덮어쓸 수 있다.
     * 순차(await) 경로만 검증하면 이 경합이 드러나지 않는다.
     */
    it('먼저 보낸 조회가 늦게 도착해도 최신 결과를 덮어쓰지 않는다', async () => {
      const badge = useTrustBadge('worker')
      let resolveSlow
      getBadge.mockReturnValueOnce(
        new Promise((resolve) => {
          resolveSlow = resolve
        })
      )
      getBadge.mockResolvedValueOnce(response({ level: 3, remainingToNextLevel: 0 }))

      const slow = badge.load() // 느린 A
      const fast = badge.load() // 빠른 B
      await fast

      expect(badge.level.value).toBe(3)

      resolveSlow(response({ level: 1, remainingToNextLevel: 8 }))
      await slow

      expect(badge.level.value).toBe(3)
      expect(badge.state.value).toBe(BADGE_STATE.READY)
    })

    it('늦게 도착한 조회의 실패도 최신 결과를 지우지 않는다', async () => {
      const badge = useTrustBadge('worker')
      let rejectSlow
      getBadge.mockReturnValueOnce(
        new Promise((_resolve, reject) => {
          rejectSlow = reject
        })
      )
      getBadge.mockResolvedValueOnce(response({ level: 2 }))

      const slow = badge.load()
      await badge.load()

      expect(badge.level.value).toBe(2)

      rejectSlow({ response: { status: 500 } })
      await slow

      expect(badge.state.value).toBe(BADGE_STATE.READY)
      expect(badge.level.value).toBe(2)
    })

    it('역할이 바뀐 재조회는 이전 역할의 뱃지를 그대로 두지 않는다', async () => {
      // 역할 불일치는 개발 중 원인을 남긴다 — 여기서는 출력만 가로챈다.
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
      const badge = useTrustBadge('worker')
      getBadge.mockResolvedValue(response({ badgeType: 'TRUST_WORKER', level: 2 }))
      await badge.load()
      expect(badge.role.value).toBe('worker')

      getBadge.mockResolvedValue(response({ badgeType: 'TRUST_OWNER', level: 3 }))
      await badge.load()

      expect(badge.state.value).toBe(BADGE_STATE.MISMATCH)
      expect(badge.badge.value).toBeNull()
      warn.mockRestore()
    })
  })
})
