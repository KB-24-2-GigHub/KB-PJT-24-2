<script setup>
/**
 * 상태 칩 — 근무/거래/정산/오늘일정 상태를 라벨+색+아이콘으로 통일 표기.
 *
 * 라벨·색은 utils/constants 의 단일 소스에서, 아이콘은 여기서 매핑한다
 * (assets/README.md 리스트 상태 표시 규약 준수).
 *
 * 사용: <StatusChip :status="workCase.status" kind="workCase" />
 *   kind: 'workCase' | 'tx' | 'settle' | 'escrow'
 */
import {
  Ban,
  CircleCheck,
  CircleX,
  Clock,
  FileCheck,
  FileText,
  Loader,
  Lock,
  Pause,
  RotateCcw,
  TriangleAlert,
  UserX
} from 'lucide-vue-next'
import { computed } from 'vue'

import { DERIVED_LATE_STATUS, WORK_CASE_STATUS } from '@/constants/workCaseStatus'
import { ESCROW_STATUS, SETTLE_STATUS, TX_STATUS } from '@/utils/constants'

const props = defineProps({
  status: { type: String, required: true },
  kind: { type: String, default: 'workCase' }
})

const LABEL_MAPS = {
  workCase: WORK_CASE_STATUS,
  tx: TX_STATUS,
  settle: SETTLE_STATUS,
  escrow: ESCROW_STATUS
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
  // 정산·거래(settlements.status 7종 — ck_settlements_status)
  HOLD: Lock,
  SETTLED: CircleCheck,
  REFUNDED: RotateCcw,
  DONE: CircleCheck,
  WAITING: Clock,
  SCHEDULED: Clock,
  PROCESSING: Loader,
  FAILED: CircleX,
  ON_HOLD: Pause,
  // 에스크로(escrows.status 5종 — ck_escrows_status). REFUNDED·ON_HOLD는 위와 공유.
  // HELD는 settle/tx의 HOLD와 철자가 달라 별도 키가 필요하다(공유 시 UNFUNDED와 같은 기본 아이콘으로 떨어짐).
  UNFUNDED: Clock,
  HELD: Lock,
  RELEASED: CircleCheck,
  // 'LATE'는 work_cases.status 8종에 없는 파생 표시값이다(workCaseStatus.js의
  // displayWorkCaseStatus 참고) — READY·체크인 전 구간을 표시만 지각으로 바꾼다.
  LATE: TriangleAlert
}

const meta = computed(() => {
  if (props.kind === 'workCase' && props.status === 'LATE') return DERIVED_LATE_STATUS
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
