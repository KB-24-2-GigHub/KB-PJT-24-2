<script setup>
/**
 * 마이페이지 신뢰 뱃지 카드의 본문 — 진행바 + 누적/정상 건수 + 다음 등급 안내 + 상태 안내.
 * FE 정의문(*안심정산이란?…)은 카드가 아니라 `TrustBadgeLevelModal`(i버튼) 맨 위에 있다.
 *
 * OWNER·WORKER 마이페이지가 같은 표시 규칙을 쓴다. 두 화면에 복사해 두면 상태 체인이나
 * 문구를 바꿀 때 한쪽만 고쳐 서로 어긋나므로 여기 한 곳에서 소유한다.
 *
 * 등급 그림(`TrustBadge`)은 프로필 상단 우측에 붙고 이 본문은 카드 아래쪽에 붙어서 DOM 부모가
 * 서로 다르다. 한 컴포넌트가 두 자리를 동시에 차지할 수 없어 그림은 뷰에 남기고, 실제로
 * 중복이던 본문만 여기로 옮겼다. 대신 두 조각이 같은 판정을 쓰도록 `useTrustBadge` 인스턴스를
 * 통째로 받는다 — 뷰가 상태를 따로 해석할 여지를 남기지 않는다.
 */
import { computed } from 'vue'

import { BADGE_STATE } from '@/composables/useTrustBadge'

const props = defineProps({
  /**
   * `useTrustBadge()` 가 돌려준 객체를 **그대로** 넘긴다. 뷰와 카드가 같은 인스턴스를 공유해야
   * 그림과 본문이 서로 다른 상태를 그리지 않는다.
   *
   * `reactive()` 로 감싸 넘기면 ref 가 언래핑돼 `.value` 가 undefined 가 되고 조용히 깨진다.
   * validator 는 Vue 의 `validateProps` 안에서만 돌아 프로덕션 번들에서는 사라지지만, 이건
   * 사용자 데이터가 아니라 호출자 실수를 잡는 장치라 개발 중 경고로 충분하다.
   */
  model: {
    type: Object,
    required: true,
    // defineProps 는 setup 밖으로 끌어올려져 지역 변수를 참조할 수 없어 목록을 그대로 적는다.
    validator: (value) =>
      [
        'badge',
        'state',
        'maxLevel',
        'countMetRatioShort',
        'nextLevelLabel',
        'progressPercent',
        'showProgress',
        'normalCount',
        'totalCount',
        'normalPercent',
        'nextThresholdPercent',
        'totalLabel',
        'normalLabel',
        'remainingLabel'
      ].every((key) => value?.[key] != null && 'value' in value[key])
  }
})

const state = computed(() => props.model.state.value)
const badge = computed(() => props.model.badge.value)
const maxLevel = computed(() => props.model.maxLevel.value)
const countMetRatioShort = computed(() => props.model.countMetRatioShort.value)
const nextLevelLabel = computed(() => props.model.nextLevelLabel.value)
const progressPercent = computed(() => props.model.progressPercent.value)
const showProgress = computed(() => props.model.showProgress.value)
const normalCount = computed(() => props.model.normalCount.value)
const totalCount = computed(() => props.model.totalCount.value)
const normalPercent = computed(() => props.model.normalPercent.value)
const nextThresholdPercent = computed(() => props.model.nextThresholdPercent.value)
const totalLabel = computed(() => props.model.totalLabel.value)
const normalLabel = computed(() => props.model.normalLabel.value)
const remainingLabel = computed(() => props.model.remainingLabel.value)
</script>

<template>
  <p v-if="state === BADGE_STATE.LOADING" class="badge-notice">뱃지 정보를 불러오는 중이에요…</p>

  <template v-else-if="state === BADGE_STATE.READY">
    <!--
      남은 건수가 0이면 진행바는 항상 100% 다. 3단계는 그게 맞지만 "건수 충족·비율 부족"에서는
      가득 찬 바가 바로 아래 문구와 정면으로 모순된다(스크린리더도 "100 퍼센트"를 읽는다).
      그 상태에서는 바를 내보내지 않고 문구가 상황을 설명한다.
    -->
    <!--
      분모는 다음 등급의 누적 문턱이라 "직전 등급 대비"가 아니라 "누적 목표 대비" 진행이다.
      2단계에 갓 오르면 20/30 이라 바가 67% 에서 시작한다 — 라벨이 없으면 스크린리더가
      맥락 없는 숫자만 읽으므로 무엇에 대한 진행률인지 이름을 붙인다.
    -->
    <div
      v-if="showProgress"
      class="bar"
      role="progressbar"
      aria-label="다음 등급까지 진행률"
      :aria-valuenow="progressPercent"
      aria-valuemin="0"
      aria-valuemax="100"
    >
      <div class="bar__fill" :style="{ width: progressPercent + '%' }"></div>
    </div>

    <!--
      normalCount·totalCount는 SPEC-432-01 이 추가한 구조화된 값이다. 서버 문장(criterionDesc)을
      그대로 노출하던 이전 방식 대신, 화면이 직접 "N건 중 M건 (P%)" 형태로 조립한다.
      비율(normalPercent)은 표시 전용 반올림 값이라 등급 판정에는 쓰지 않는다.
    -->
    <p class="badge-counts">
      누적 {{ totalLabel }} {{ totalCount }}건 중 {{ normalLabel }} {{ normalCount }}건 ({{
        normalPercent
      }}%)
    </p>

    <p class="level-remaining">
      <template v-if="maxLevel">최고 등급이에요.</template>
      <template v-else-if="countMetRatioShort">
        다음 레벨 {{ nextLevelLabel }} 건수 조건은 채웠어요. 정상 비율이 더 필요해요.
      </template>
      <template v-else>
        다음 {{ nextLevelLabel }}까지 {{ remainingLabel }} {{ badge.remainingToNextLevel }}건,
        {{ badge.criterionLabel }} {{ nextThresholdPercent }}%이상 유지 필요
      </template>
    </p>
  </template>

  <p v-else-if="state === BADGE_STATE.FORBIDDEN" class="badge-notice">뱃지를 볼 권한이 없어요.</p>

  <p v-else-if="state === BADGE_STATE.EMPTY || state === BADGE_STATE.MISMATCH" class="badge-notice">
    뱃지 정보를 표시할 수 없어요.
  </p>

  <p v-else class="badge-notice">뱃지 정보를 불러오지 못했어요.</p>
</template>

<style scoped>
/* 진행바 색은 역할별 강조색을 쓰는 뷰가 --badge-progress 로 넘긴다.
   위쪽 여백은 프로필 영역(이름·타이틀·뱃지 아이콘)과의 간격을 좁혀 카드 상단을 조금 더
   압축해 보이게 한다. */
.bar {
  height: 8px;
  margin-top: var(--space-md);
  overflow: hidden;
  background: var(--color-bg);
  border-radius: var(--radius-pill);
}
.bar__fill {
  height: 100%;
  background: var(--badge-progress, var(--color-text-sub));
  border-radius: var(--radius-pill);
}

.badge-counts {
  margin-top: var(--space-sm);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text);
}
.level-remaining {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.badge-notice {
  margin-top: var(--space-lg);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
</style>
