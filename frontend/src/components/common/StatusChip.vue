<script setup>
/**
 * 상태 칩 — 근무/거래/정산/오늘일정 상태를 라벨+색+아이콘으로 통일 표기.
 *
 * 라벨·색은 utils/constants 의 단일 소스에서, 아이콘은 여기서 매핑한다
 * (assets/README.md 리스트 상태 표시 규약 준수).
 *
 * 사용: <StatusChip :status="workCase.status" kind="workCase" />
 *   kind: 'workCase' | 'tx' | 'settle'
 */
import {
  Ban,
  CircleCheck,
  Clock,
  FileCheck,
  FileText,
  Loader,
  Lock,
  RotateCcw,
  TriangleAlert,
  UserX
} from 'lucide-vue-next'
import { computed } from 'vue'

import { WORK_CASE_STATUS } from '@/constants/workCaseStatus'
import { SETTLE_STATUS, TX_STATUS } from '@/utils/constants'

const props = defineProps({
  status: { type: String, required: true },
  kind: { type: String, default: 'workCase' }
})

const LABEL_MAPS = {
  workCase: WORK_CASE_STATUS,
  tx: TX_STATUS,
  settle: SETTLE_STATUS
}

// 상태값(enum) → lucide 아이콘. 서로 다른 kind 가 같은 상태값을 공유한다.
const ICONS = {
  // 근무(work_case) 8단계
  DRAFT: FileText,
  ACCEPTED: FileCheck,
  READY: Clock,
  IN_PROGRESS: Loader,
  CHECK_OUT_MISSING: TriangleAlert,
  COMPLETED: CircleCheck,
  NO_SHOW: UserX,
  CANCELED: Ban,
  // 정산·거래
  HOLD: Lock,
  SETTLED: CircleCheck,
  REFUNDED: RotateCcw,
  DONE: CircleCheck
}

const meta = computed(() => {
  const map = LABEL_MAPS[props.kind] ?? WORK_CASE_STATUS
  return map[props.status] ?? { label: props.status, color: 'var(--color-text-sub)' }
})
const icon = computed(() => ICONS[props.status] ?? Clock)
</script>

<template>
  <span class="chip" :style="{ color: meta.color }">
    <component :is="icon" :size="13" />
    {{ meta.label }}
  </span>
</template>

<style scoped>
.chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  white-space: nowrap;
}
</style>
