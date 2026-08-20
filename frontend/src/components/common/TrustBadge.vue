<script setup>
/**
 * 신뢰 뱃지 그림 — 사장 '안심일터' / 알바생 '성실근로자'.
 *
 * 마이페이지 뱃지 카드와 초대 확인(사장 뱃지)에서 공용.
 * level 0 = 미부여 → lv0 그림('이력 쌓는 중').
 *
 * 등급 문턱(SPEC-178-06 의 누적 10/20/30건, 정상 비율 80/90/100%)은 서버 소유다.
 * 이 컴포넌트는 등급을 판정하지 않고 받은 level 을 그림으로만 옮긴다 — 문턱이 바뀌어도
 * 여기는 바뀌지 않아야 한다.
 *
 * 뱃지 정의 문구(*성실근로란?…)는 화면(마이페이지)에서 별도로 붙인다.
 */
import { computed } from 'vue'

import ownerLv0 from '@/assets/images/badges/badge-owner-lv0.png'
import ownerLv1 from '@/assets/images/badges/badge-owner-lv1.png'
import ownerLv2 from '@/assets/images/badges/badge-owner-lv2.png'
import ownerLv3 from '@/assets/images/badges/badge-owner-lv3.png'
import workerLv0 from '@/assets/images/badges/badge-worker-lv0.png'
import workerLv1 from '@/assets/images/badges/badge-worker-lv1.png'
import workerLv2 from '@/assets/images/badges/badge-worker-lv2.png'
import workerLv3 from '@/assets/images/badges/badge-worker-lv3.png'

const props = defineProps({
  role: { type: String, required: true }, // 'owner' | 'worker'
  level: { type: Number, required: true }, // 0~3 (0 = 미부여)
  size: { type: Number, default: 48 }
})

const BADGES = {
  owner: [ownerLv0, ownerLv1, ownerLv2, ownerLv3],
  worker: [workerLv0, workerLv1, workerLv2, workerLv3]
}

/**
 * 그릴 수 있는 입력인지 런타임에서 판정한다.
 *
 * `required: true` 나 `validator` 는 Vue 의 `validateProps` 가 `__DEV__` 로 감싸여 있어
 * 프로덕션 번들에서 통째로 사라지고, 애초에 throw 하지도 않는다. 즉 개발 중 경고일 뿐
 * "잘못된 입력이 잘못 그려지는 것"을 막지 못한다.
 *
 * 막아야 하는 구체적인 사고는 이것이다 — level 을 빠뜨린 호출(`<TrustBadge role="worker" />`)이
 * `BADGES[role][undefined]` 로 떨어져 **3단계 사용자를 미부여(lv0) 그림으로**
 * 조용히 그리는 것. 그래서 판정을 렌더 경로에 둔다.
 */
const valid = computed(
  () =>
    Object.hasOwn(BADGES, props.role) &&
    Number.isInteger(props.level) &&
    props.level >= 0 &&
    props.level < BADGES[props.role].length
)

const src = computed(() => (valid.value ? BADGES[props.role][props.level] : null))
</script>

<template>
  <!-- 그릴 수 없는 입력은 미부여로 위장하지 않고 아무것도 내보내지 않는다. -->
  <div v-if="valid" class="trust-badge">
    <img :src="src" :alt="`${role} 뱃지 ${level}단계`" :width="size" :height="size" />
  </div>
</template>

<style scoped>
.trust-badge {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-xs);
}
</style>
