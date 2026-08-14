<script setup>
/**
 * [F] 알바생 근로관리  ·  /worker/work  ·  WORKER  (탭 화면)
 * 근무 히스토리 리스트(상태 뱃지). 항목 클릭 → 근무 정보 상세.
 * 연계 API: GET /worker/work-cases  →  @/services/worker (listWorkerWorkCases)
 * 문의하기·임금분쟁 신고는 상세 화면(WorkerWorkCaseDetailView) 하단 버튼에서 진입한다.
 * 공통: StatusChip(근무/정산 상태) · 항목 클릭 → /worker/work/work-cases/:workCaseId
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import EmptyState from '@/components/common/EmptyState.vue'
import StatusChip from '@/components/common/StatusChip.vue'
import { listWorkerWorkCases } from '@/services/worker'
import { useUiStore } from '@/stores/ui'
import {
  formatDate,
  formatKRW,
  formatSeoulDateKey,
  formatSeoulDateTime,
  formatSeoulTimeRange
} from '@/utils/format'

const router = useRouter()
const ui = useUiStore()

const workCases = ref([])
const loading = ref(true)
const loadError = ref(null)

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const { content } = await listWorkerWorkCases()
    workCases.value = content ?? []
  } catch (error) {
    loadError.value = error
    const message =
      error?.code === 'FEATURE_UNAVAILABLE'
        ? '근무 내역은 현재 준비 중인 기능입니다.'
        : '근무 내역을 불러오지 못했습니다.'
    ui.toast(message, { type: error?.code === 'FEATURE_UNAVAILABLE' ? 'info' : 'danger' })
  } finally {
    loading.value = false
  }
}

function goDetail(workCase) {
  router.push(`/worker/work/work-cases/${workCase.workCaseId}`)
}
</script>

<template>
  <div class="worker-work">
    <h1 class="page-title">근무 내역</h1>

    <p v-if="loading" class="loading">불러오는 중…</p>

    <EmptyState
      v-else-if="loadError"
      :message="
        loadError.code === 'FEATURE_UNAVAILABLE'
          ? '근무 내역은 현재 준비 중인 기능입니다.'
          : '근무 내역을 불러오지 못했습니다.'
      "
    />

    <EmptyState v-else-if="workCases.length === 0" message="아직 근무 내역이 없어요." />

    <ul v-else class="work-case-list">
      <li v-for="workCase in workCases" :key="workCase.workCaseId" class="work-case">
        <button type="button" class="work-case-main" @click="goDetail(workCase)">
          <div class="work-case-head">
            <span class="workplace">{{ workCase.workplaceName }}</span>
            <span class="date">{{ formatDate(formatSeoulDateKey(workCase.startsAt)) }}</span>
          </div>
          <div class="work-case-sub">
            <span class="time">{{ formatSeoulTimeRange(workCase.startsAt, workCase.endsAt) }}</span>
            <span class="wage">{{ formatKRW(workCase.dailyWage) }}</span>
          </div>
          <div class="work-case-status">
            <StatusChip :status="workCase.status" kind="workCase" />
            <StatusChip :status="workCase.settlementStatus" kind="settle" />
          </div>
          <p v-if="workCase.settlementDueAt" class="due-at">
            {{ formatSeoulDateTime(workCase.settlementDueAt) }} 지급 예정
          </p>
        </button>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.page-title {
  font-size: var(--text-xl);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.loading {
  margin-top: var(--space-lg);
  text-align: center;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.work-case-list {
  margin-top: var(--space-md);
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}
.work-case {
  padding: var(--space-lg);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.work-case-main {
  width: 100%;
  text-align: left;
}
.work-case-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-sm);
}
.workplace {
  font-size: var(--text-lg);
  font-weight: var(--weight-medium);
  color: var(--color-text);
}
.date {
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.work-case-sub {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-top: var(--space-xs);
}
.time {
  font-size: var(--text-md);
  color: var(--color-text-sub);
}
.wage {
  font-size: var(--text-md);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.work-case-status {
  display: flex;
  gap: var(--space-md);
  margin-top: var(--space-sm);
}
.due-at {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
</style>
