import { computed, ref } from 'vue'

import { getBadge } from '@/services/users'
import { BADGE_TYPE } from '@/utils/constants'

/**
 * `GET /api/users/me/badge`(SPEC-178-06) 응답을 마이페이지 표시 상태로 옮긴다.
 *
 * 이 Composable 이 지키는 계약은 하나다 — **등급 판정 기준을 여기서 다시 계산하지 않는다.**
 * 산정 문턱(누적 10/20/30건, 정상 비율 80/90/100%)은 서버 소유이고, 화면은 서버가 준
 * `level`·`recentCount`·`remainingToNextLevel` 을 그대로 읽는다. 진행률만은 두 응답 값의
 * 비율로 만드는데, 이는 등급 판정이 아니라 이미 확정된 값의 표현이다.
 *
 * `MAX_LEVEL` 은 산정 문턱이 아니라 **그릴 수 있는 등급 그림의 범위**다. `TrustBadge` 에
 * 1~3단계 SVG 세 장만 있으므로 FE 는 이 범위를 알 수밖에 없다. 문턱이 10/20/30 에서 바뀌어도
 * 이 파일은 바뀌지 않지만, 서버가 4단계를 새로 만들면 에셋과 함께 여기도 바뀌어야 한다.
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

/** 그릴 수 있는 최고 등급. 산정 문턱이 아니라 보유한 뱃지 그림의 범위다(위 설명 참고). */
export const MAX_LEVEL = 3

/**
 * 표시 의미를 결정하는 승인 필드가 모두 승인된 모양인지 본다.
 *
 * `criterionDesc` 는 일부러 넣지 않는다. 진행 설명문 한 줄이 비었다고 뱃지 전체를 숨기면
 * 등급이라는 더 중요한 정보까지 잃는다 — 그 필드는 표시 쪽에서 `v-if` 로 흘려보낸다.
 */
function isApprovedShape(data) {
  if (!data || typeof data !== 'object') return false
  // Object.hasOwn 이어야 한다. `BADGE_TYPE['constructor']` 같은 상속 프로퍼티는 truthy 라
  // 브래킷 조회로는 멤버십 검사가 되지 않고, 빈 응답이 역할 불일치로 오분류된다.
  if (!Object.hasOwn(BADGE_TYPE, data.badgeType)) return false
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

  // 늦게 도착한 이전 요청이 최신 결과를 덮어쓰지 못하게 한다(useDocumentPreview 와 같은 방식).
  let requestSequence = 0

  const ready = computed(() => state.value === BADGE_STATE.READY)
  const level = computed(() => (ready.value ? badge.value.level : 0))

  /** 응답이 그리라고 지목한 역할. READY 일 때는 항상 expectedRole 과 같다. */
  const role = computed(() => (ready.value ? BADGE_TYPE[badge.value.badgeType].role : null))

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
   *
   * 남은 건수가 0이면 분모가 recentCount 와 같아져 항상 100% 가 된다. 3단계는 그게 맞지만
   * 비율 부족은 "다 찼는데 안 오른다"가 되어 옆 문구와 모순된다 — 그래서 그 상태에서는
   * 진행바 자체를 내보내지 않는다(`showProgress`).
   */
  const progressPercent = computed(() => {
    if (!ready.value) return 0
    const total = badge.value.recentCount + badge.value.remainingToNextLevel
    if (total <= 0) return badge.value.level > 0 ? 100 : 0
    return Math.min(100, Math.round((badge.value.recentCount / total) * 100))
  })

  /** 건수 진행이 의미를 갖는 상태에서만 진행바를 그린다(위 progressPercent 설명 참고). */
  const showProgress = computed(() => ready.value && !countMetRatioShort.value)

  /** FE 소유 정의문. 서버 `criterionDesc`(진행 설명문)를 대체하지 않고 함께 보여준다. */
  const definition = computed(() =>
    ready.value ? (BADGE_TYPE[badge.value.badgeType].definition ?? '') : ''
  )

  async function load() {
    const sequence = ++requestSequence
    state.value = BADGE_STATE.LOADING
    badge.value = null
    try {
      const data = await getBadge()
      if (sequence !== requestSequence) return
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
      if (sequence !== requestSequence) return
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
    maxLevel,
    countMetRatioShort,
    nextLevelLabel,
    progressPercent,
    showProgress,
    definition,
    load
  }
}
