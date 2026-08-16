import { computed, ref } from 'vue'

import { getBadge } from '@/services/users'
import { BADGE_TYPE } from '@/utils/constants'

/**
 * `GET /api/users/me/badge`(SPEC-178-06) 응답을 마이페이지 표시 상태로 옮긴다.
 *
 * 이 Composable 이 지키는 계약은 하나다 — **등급도 이력 개수도 여기서 다시 계산하지 않는다.**
 * 문턱(누적 10/20/30건, 정상 비율 80/90/100%)은 서버 소유이고, 화면은 서버가 준
 * `level`·`recentCount`·`remainingToNextLevel` 을 그대로 읽는다. 진행률만은 두 응답 값의
 * 비율로 만드는데, 이는 등급 판정이 아니라 이미 확정된 값의 표현이다.
 *
 * 뱃지 캐시는 두지 않는다. 역할 전환·재로그인은 화면 재마운트를 뜻하고 그때마다 다시
 * 조회하므로, 이전 사용자의 뱃지가 남을 자리 자체가 없다.
 */

/** 로딩·성공·빈 응답·역할 불일치·권한 오류·그 밖의 실패를 서로 다른 상태로 구분한다. */
export const BADGE_STATE = {
  LOADING: 'loading',
  READY: 'ready',
  EMPTY: 'empty',
  MISMATCH: 'mismatch',
  FORBIDDEN: 'forbidden',
  ERROR: 'error'
}

const MAX_LEVEL = 3

/** 표시 의미를 결정하는 승인 필드가 모두 승인된 모양인지 본다. */
function isApprovedShape(data) {
  if (!data || typeof data !== 'object') return false
  if (!BADGE_TYPE[data.badgeType]) return false
  if (!Number.isInteger(data.level) || data.level < 0 || data.level > MAX_LEVEL) return false
  if (!Number.isInteger(data.recentCount) || data.recentCount < 0) return false
  if (!Number.isInteger(data.remainingToNextLevel) || data.remainingToNextLevel < 0) return false
  return typeof data.criterionLabel === 'string' && data.criterionLabel.trim() !== ''
}

/**
 * @param {'owner'|'worker'} expectedRole 이 화면이 그릴 수 있는 유일한 역할.
 *   응답 `badgeType` 이 다른 역할을 가리키면 그리지 않고 MISMATCH 로 남긴다 — WORKER 화면에
 *   OWNER 산정치를 성실근로 뱃지로 그리던 과거 Mock 경로를 코드로 막는다.
 */
export function useTrustBadge(expectedRole) {
  const badge = ref(null)
  const state = ref(BADGE_STATE.LOADING)

  const ready = computed(() => state.value === BADGE_STATE.READY)
  const level = computed(() => (ready.value ? badge.value.level : 0))

  /** 응답이 그리라고 지목한 역할. READY 일 때는 항상 expectedRole 과 같다. */
  const role = computed(() => (ready.value ? BADGE_TYPE[badge.value.badgeType].role : null))

  /** 이력이 없어 아직 등급이 없는 상태(0단계). 오류가 아니다. */
  const unawarded = computed(() => ready.value && badge.value.level === 0)

  /** 다음 등급이 없는 상태. `remainingToNextLevel` 은 3단계에서도 0이라 이것과 구분해야 한다. */
  const maxLevel = computed(() => ready.value && badge.value.level >= MAX_LEVEL)

  /**
   * 건수 문턱은 채웠는데 정상 비율이 모자라 등급이 오르지 못한 상태.
   * SPEC-178-06 에서 `remainingToNextLevel` 이 0이 되는 두 경우 중 3단계가 아닌 쪽이다.
   * 구분하지 않으면 "0건 남음"만 보고 곧 등급이 오를 거라 읽게 된다.
   */
  const countMetRatioShort = computed(
    () => ready.value && badge.value.level < MAX_LEVEL && badge.value.remainingToNextLevel === 0
  )

  const nextLevelLabel = computed(() => (maxLevel.value ? '최고 등급' : `Lv.${level.value + 1}`))

  /**
   * 다음 등급 건수 문턱까지의 진행률. 분모는 서버가 준 두 값의 합
   * (`recentCount + remainingToNextLevel`)이라 문턱 숫자를 화면이 알 필요가 없다.
   * 3단계와 비율 부족은 남은 건수가 0이라 자연히 100% 가 되고, 그때 사용자가 오해하지
   * 않도록 `maxLevel`·`countMetRatioShort` 가 문구를 따로 책임진다.
   */
  const progressPercent = computed(() => {
    if (!ready.value) return 0
    const total = badge.value.recentCount + badge.value.remainingToNextLevel
    if (total <= 0) return badge.value.level > 0 ? 100 : 0
    return Math.min(100, Math.round((badge.value.recentCount / total) * 100))
  })

  /** FE 소유 정의문. 서버 `criterionDesc`(진행 설명문)를 대체하지 않고 함께 보여준다. */
  const definition = computed(() =>
    ready.value ? (BADGE_TYPE[badge.value.badgeType].definition ?? '') : ''
  )

  async function load() {
    state.value = BADGE_STATE.LOADING
    badge.value = null
    try {
      const data = await getBadge()
      if (!isApprovedShape(data)) {
        state.value = BADGE_STATE.EMPTY
        return
      }
      if (BADGE_TYPE[data.badgeType].role !== expectedRole) {
        state.value = BADGE_STATE.MISMATCH
        return
      }
      badge.value = data
      state.value = BADGE_STATE.READY
    } catch (error) {
      // 401 은 http.js 인터셉터가 온보딩으로 돌려보내므로 이 화면의 상태로 남지 않는다.
      state.value = error?.response?.status === 403 ? BADGE_STATE.FORBIDDEN : BADGE_STATE.ERROR
    }
  }

  return {
    badge,
    state,
    ready,
    role,
    level,
    unawarded,
    maxLevel,
    countMetRatioShort,
    nextLevelLabel,
    progressPercent,
    definition,
    load
  }
}
