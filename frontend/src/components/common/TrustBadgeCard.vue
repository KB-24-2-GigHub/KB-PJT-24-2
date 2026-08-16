<script setup>
/**
 * 마이페이지 신뢰 뱃지 카드의 본문 — 진행바 + 다음 등급 안내 + 설명 두 줄 + 상태 안내.
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
  /** `useTrustBadge()` 가 돌려준 객체 그대로. 뷰와 카드가 같은 인스턴스를 공유한다. */
  model: { type: Object, required: true }
})

const state = computed(() => props.model.state.value)
const badge = computed(() => props.model.badge.value)
const maxLevel = computed(() => props.model.maxLevel.value)
const countMetRatioShort = computed(() => props.model.countMetRatioShort.value)
const nextLevelLabel = computed(() => props.model.nextLevelLabel.value)
const progressPercent = computed(() => props.model.progressPercent.value)
const showProgress = computed(() => props.model.showProgress.value)
const definition = computed(() => props.model.definition.value)
</script>

<template>
  <p v-if="state === BADGE_STATE.LOADING" class="badge-notice">뱃지 정보를 불러오는 중이에요…</p>

  <template v-else-if="state === BADGE_STATE.READY">
    <!--
      남은 건수가 0이면 진행바는 항상 100% 다. 3단계는 그게 맞지만 "건수 충족·비율 부족"에서는
      가득 찬 바가 바로 아래 문구와 정면으로 모순된다(스크린리더도 "100 퍼센트"를 읽는다).
      그 상태에서는 바를 내보내지 않고 문구가 상황을 설명한다.
    -->
    <div
      v-if="showProgress"
      class="bar"
      role="progressbar"
      :aria-valuenow="progressPercent"
      aria-valuemin="0"
      aria-valuemax="100"
    >
      <div class="bar__fill" :style="{ width: progressPercent + '%' }"></div>
    </div>

    <p class="level-remaining">
      <template v-if="maxLevel">최고 등급이에요.</template>
      <template v-else-if="countMetRatioShort">
        다음 레벨 {{ nextLevelLabel }} 건수 조건은 채웠어요. 정상 비율이 더 필요해요.
      </template>
      <template v-else>
        다음 레벨 {{ nextLevelLabel }}까지 {{ badge.criterionLabel }}
        {{ badge.remainingToNextLevel }}건 남음
      </template>
    </p>

    <!--
      서버가 계산한 진행 설명문. 화면이 건수·비율을 다시 문장으로 만들지 않는다.
      이 문장이 비었다고 뱃지를 통째로 숨기지는 않되(등급이 더 중요한 정보다), 빈 문단이
      여백만 남기지 않도록 여기서 거른다.
    -->
    <p v-if="badge.criterionDesc" class="badge-desc">{{ badge.criterionDesc }}</p>
    <p v-if="definition" class="badge-definition">{{ definition }}</p>
  </template>

  <p v-else-if="state === BADGE_STATE.FORBIDDEN" class="badge-notice">뱃지를 볼 권한이 없어요.</p>

  <p v-else-if="state === BADGE_STATE.EMPTY || state === BADGE_STATE.MISMATCH" class="badge-notice">
    뱃지 정보를 표시할 수 없어요.
  </p>

  <p v-else class="badge-notice">뱃지 정보를 불러오지 못했어요.</p>
</template>

<style scoped>
/* 진행바 색은 역할별 강조색을 쓰는 뷰가 --badge-progress 로 넘긴다. */
.bar {
  height: 8px;
  margin-top: var(--space-lg);
  overflow: hidden;
  background: var(--color-bg);
  border-radius: var(--radius-pill);
}
.bar__fill {
  height: 100%;
  background: var(--badge-progress, var(--color-text-sub));
  border-radius: var(--radius-pill);
}

.level-remaining {
  margin-top: var(--space-sm);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text);
}
.badge-desc {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.badge-definition {
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
