<script setup>
import { computed } from 'vue'

import { formatSeoulDateTime } from '@/utils/format'

const props = defineProps({
  reports: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  refreshable: { type: Boolean, default: true }
})

const emit = defineEmits(['refresh'])

const statusMeta = {
  OPEN: { label: '접수됨 · AI 검토 대기', tone: 'waiting' },
  UNDER_REVIEW: { label: '검토 중 · 보류 유지', tone: 'waiting' },
  RESOLVED: { label: '종료 · 정산 재개', tone: 'done' },
  REJECTED: { label: '종료 · 신고 기각', tone: 'done' },
  CANCELED: { label: '취소됨', tone: 'done' }
}

function label(report) {
  if (report.demoReview?.decision === 'NEEDS_MORE_INFO') return '추가 자료 필요 · 보류 유지'
  return statusMeta[report.status]?.label ?? report.status
}

function tone(report) {
  return statusMeta[report.status]?.tone ?? 'waiting'
}

function summary(report) {
  return report.demoReview?.summary || report.resolution || ''
}

function reviewedAt(report) {
  return report.demoReview?.reviewedAt || report.resolvedAt
}

const hasReports = computed(() => props.reports.length > 0)
</script>

<template>
  <section class="dispute-panel">
    <header class="panel-head">
      <div>
        <h3>분쟁 처리 현황</h3>
        <p>외부 분쟁조정 시스템을 흉내 낸 AI DEMO이며, 법적 판단이나 자문이 아닙니다.</p>
      </div>
      <button
        v-if="refreshable"
        type="button"
        class="refresh"
        :disabled="loading"
        @click="emit('refresh')"
      >
        {{ loading ? '확인 중…' : '새로고침' }}
      </button>
    </header>

    <p v-if="loading && !hasReports" class="empty">분쟁 상태를 확인하고 있어요.</p>
    <p v-else-if="!hasReports" class="empty">접수된 분쟁이 없습니다.</p>

    <article v-for="report in reports" v-else :key="report.reportId" class="report">
      <div class="report-head">
        <strong>{{ report.title }}</strong>
        <span class="state" :class="`state--${tone(report)}`">{{ label(report) }}</span>
      </div>
      <p class="content">{{ report.content }}</p>
      <div v-if="summary(report)" class="result">
        <strong>DEMO 검토 요약</strong>
        <p>{{ summary(report) }}</p>
        <ul v-if="report.demoReview?.reasonCodes?.length" class="reason-codes">
          <li v-for="code in report.demoReview.reasonCodes" :key="code">{{ code }}</li>
        </ul>
      </div>
      <p class="time">
        접수 {{ formatSeoulDateTime(report.createdAt) }}
        <template v-if="reviewedAt(report)">
          · 검토 {{ formatSeoulDateTime(reviewedAt(report)) }}
        </template>
      </p>
    </article>
  </section>
</template>

<style scoped>
.dispute-panel {
  padding: var(--space-lg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}
.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-md);
}
.panel-head h3 {
  font-size: var(--text-lg);
  font-weight: var(--weight-bold);
}
.panel-head p,
.empty,
.time {
  margin-top: var(--space-xs);
  color: var(--color-text-sub);
  font-size: var(--text-sm);
}
.refresh {
  flex-shrink: 0;
  border: 0;
  background: transparent;
  color: var(--color-primary);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
}
.refresh:disabled {
  color: var(--color-text-sub);
}
.report {
  margin-top: var(--space-md);
  padding-top: var(--space-md);
  border-top: 1px solid var(--color-border);
}
.report-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-sm);
}
.state {
  flex-shrink: 0;
  padding: 2px var(--space-xs);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
}
.state--waiting {
  background: var(--color-warning-light, #fff4d6);
  color: var(--color-text);
}
.state--done {
  background: var(--color-bg);
  color: var(--color-text-sub);
}
.content {
  margin-top: var(--space-sm);
  white-space: pre-wrap;
  line-height: 1.55;
}
.result {
  margin-top: var(--space-sm);
  padding: var(--space-md);
  border-radius: var(--radius-sm);
  background: var(--color-bg);
}
.result strong {
  font-size: var(--text-sm);
}
.result p {
  margin-top: var(--space-xs);
  line-height: 1.5;
}
.reason-codes {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-xs);
  margin-top: var(--space-sm);
  padding: 0;
  list-style: none;
}
.reason-codes li {
  padding: 2px var(--space-xs);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  color: var(--color-text-sub);
  font-size: var(--text-sm);
}
</style>
