<script setup>
/**
 * [F] 알바생 근로관리  ·  /worker/work  ·  WORKER  (탭 화면)
 * 근무 히스토리 리스트(상태 뱃지). 항목 클릭 → 근무 정보 상세.
 * 연계 API: GET /worker/work-cases  →  @/services/worker (listWorkerWorkCases)
 * 문의하기·임금분쟁 신고는 상세 화면(WorkerWorkCaseDetailView) 하단 버튼에서 진입한다.
 * 공통: StatusChip(근무/정산/예치 상태) · 항목 클릭 → /worker/work/work-cases/:workCaseId
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import EmptyState from '@/components/common/EmptyState.vue'
import StatusChip from '@/components/common/StatusChip.vue'
import { displayWorkCaseStatus } from '@/constants/workCaseStatus'
import { listWorkerWorkCases } from '@/services/worker'
import { useUiStore } from '@/stores/ui'
import {
  formatDate,
  formatKRW,
  formatSeoulDateKey,
  formatSeoulDateTime,
  formatSeoulTimeRange
} from '@/utils/format'
import { hasNextPage } from '@/utils/page'

const router = useRouter()
const ui = useUiStore()

const workCases = ref([])
const loading = ref(true)
const loadingMore = ref(false)
const loadError = ref(null)
const nextPage = ref(0)
const hasMore = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const { content, page } = await listWorkerWorkCases({ page: 0 })
    workCases.value = content ?? []
    nextPage.value = 1
    hasMore.value = hasNextPage(page)
  } catch (error) {
    loadError.value = error
    // 이전 조회 결과를 남기면 오류 화면 아래에 낡은 목록이 함께 보인다.
    workCases.value = []
    hasMore.value = false
    const message =
      error?.code === 'FEATURE_UNAVAILABLE'
        ? '근무 내역은 현재 준비 중인 기능입니다.'
        : '근무 내역을 불러오지 못했습니다.'
    ui.toast(message, { type: error?.code === 'FEATURE_UNAVAILABLE' ? 'info' : 'danger' })
  } finally {
    loading.value = false
  }
}

/**
 * 다음 Page 를 이어 붙인다.
 *
 * 목록 API 는 기본 20건 Page 다. 첫 Page 만 읽으면 21번째부터는 표시도 오류도 없이
 * 사라져 사용자는 그 기록이 없다고 믿게 된다. 갈아끼우지 않고 뒤에 붙여야 한다.
 * 실패해도 이미 불러온 목록은 지우지 않는다 — 더 보려던 시도가 보고 있던 것까지 없앨
 * 이유가 없다.
 */
async function loadMore() {
  if (loadingMore.value || !hasMore.value) return
  loadingMore.value = true
  try {
    const { content, page } = await listWorkerWorkCases({ page: nextPage.value })
    workCases.value = [...workCases.value, ...(content ?? [])]
    nextPage.value += 1
    hasMore.value = hasNextPage(page)
  } catch {
    ui.toast('근무 내역을 더 불러오지 못했어요.', { type: 'danger' })
  } finally {
    loadingMore.value = false
  }
}

function goDetail(workCase) {
  router.push(`/worker/work/work-cases/${workCase.workCaseId}`)
}

// 상세 화면(WorkerWorkCaseDetailView)과 같은 기준 — WAITING은 수락 시점에 예약만 해 둔
// 기본값이라 뜻이 없어 항상 숨기고, NO_SHOW는 상태 칩과 취소선 금액이 이미 결과를
// 설명하므로 정산 칩을 따로 겹쳐 보여주지 않는다.
function showSettlementChip(workCase) {
  return workCase.status !== 'NO_SHOW' && workCase.settlementStatus !== 'WAITING'
}

// 예치 칩은 근무가 진행 중일 때만 "안전하게 보관 중"이라는 의미가 있다. 완료되면 정산
// 칩이 결과(지급/실패/보류)를 대신 말해주고, 노쇼는 상태 칩이 대신한다.
function showEscrowChip(workCase) {
  return (
    Boolean(workCase.escrowStatus) &&
    workCase.status !== 'NO_SHOW' &&
    workCase.status !== 'COMPLETED'
  )
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
            <span class="wage" :class="{ 'wage-voided': workCase.status === 'NO_SHOW' }">{{
              formatKRW(workCase.dailyWage)
            }}</span>
          </div>
          <div class="work-case-status">
            <StatusChip :status="displayWorkCaseStatus(workCase)" kind="workCase" />
            <StatusChip
              v-if="showSettlementChip(workCase)"
              :status="workCase.settlementStatus"
              kind="settle"
            />
            <StatusChip
              v-if="showEscrowChip(workCase)"
              :status="workCase.escrowStatus"
              kind="escrow"
            />
          </div>
          <p
            v-if="workCase.settlementStatus === 'SCHEDULED' && workCase.settlementDueAt"
            class="due-at"
          >
            {{ formatSeoulDateTime(workCase.settlementDueAt) }} 지급 예정
          </p>
        </button>
      </li>
    </ul>

    <button v-if="hasMore" type="button" class="more-btn" :disabled="loadingMore" @click="loadMore">
      {{ loadingMore ? '불러오는 중…' : '더 보기' }}
    </button>
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
.wage-voided {
  color: var(--color-text-sub);
  text-decoration: line-through;
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

/* 문서함(WorkerDocumentsView)의 '더 보기'와 같은 모양 — 두 목록이 같은 방식으로 이어진다. */
.more-btn {
  width: 100%;
  margin-top: var(--space-md);
  padding: var(--space-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-sub);
}
</style>
