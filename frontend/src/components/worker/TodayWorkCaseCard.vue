<script setup>
/**
 * 상태 표기는 workCaseStatus.js 8종 단일 소스(StatusChip)만 쓴다 — 이 카드에서 상태 문자열을
 * 따로 하드코딩하지 않는다. 체크인 후 확정된 지각은 work_case 상태가 아니라
 * attendance.isLate의 파생 표시라(workCaseStatus.js 문서 참고) 상태 칩과 별개 배지로
 * 얹는다. 체크인 전, 시작 시각만 지난 구간은 displayWorkCaseStatus가 상태 칩 자체를
 * 'LATE'로 바꿔 보여준다 — 두 표시는 체크인 여부로 갈려 겹치지 않는다.
 */
import { CalendarX, TriangleAlert } from 'lucide-vue-next'
import { computed } from 'vue'

import StatusChip from '@/components/common/StatusChip.vue'
import { displayWorkCaseStatus } from '@/constants/workCaseStatus'

const props = defineProps({
  workCase: { type: Object, default: null }
})

// 오늘 근무 후보가 없으면 서버가 todayWorkCase 자체를 null로 준다(WorkerHomeResponse).
const isEmpty = computed(() => !props.workCase)
const isLate = computed(() => !!props.workCase?.attendance?.isLate)
const displayStatus = computed(() => displayWorkCaseStatus(props.workCase))
</script>

<template>
  <section class="today-card">
    <h2 class="title">오늘의 알바</h2>

    <div v-if="isEmpty" class="empty">
      <CalendarX :size="20" />
      <span>오늘은 예정된 알바가 없어요.</span>
    </div>

    <div v-else class="work-case">
      <div class="badges">
        <StatusChip :status="displayStatus" kind="workCase" />
        <span v-if="isLate" class="badge-late">
          <TriangleAlert :size="13" />
          지각 {{ workCase.attendance.lateMinutes }}분
        </span>
      </div>
      <p class="work-case-title">{{ workCase.title }}</p>
      <p class="work-case-info">{{ workCase.workplaceName }} · {{ workCase.timeRange }}</p>
    </div>
  </section>
</template>

<style scoped>
.today-card {
  margin-top: var(--space-md);
  padding: var(--space-lg);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.title {
  font-size: var(--text-lg);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}

.empty {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-top: var(--space-md);
  color: var(--color-text-sub);
  font-size: var(--text-md);
}

.badges {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.badge-late {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-warning);
}

.work-case-title {
  margin-top: var(--space-sm);
  font-size: var(--text-lg);
  font-weight: var(--weight-medium);
  color: var(--color-text);
}

.work-case-info {
  margin-top: var(--space-xs);
  font-size: var(--text-md);
  color: var(--color-text-sub);
}
</style>
