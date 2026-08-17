<script>
/**
 * 근태관리 검색·필터 바텀시트 — /owner/attendance 에서 사용.
 *
 * 사장 홈의 송금상세 필터(TransactionFilterSheet)와 같은 구조·조작 방식을 따른다.
 * 필터는 프론트에서 목록을 재계산하지 않고 서버 파라미터로만 전달한다
 * (서버가 최종 권위 — docs/rules/domain.md). buildAttendanceFilterParams 는
 * 빈 값/기본값을 제외한 GET /workplaces/{id}/work-cases 파라미터를 만든다.
 */
export const DEFAULT_FILTER = {
  keyword: '',
  status: 'ALL',
  from: '',
  to: ''
}

/**
 * 필터 초안 → 서버 파라미터(빈 값·기본값 제외). 순수 함수라 단위 테스트 대상.
 * 키 이름은 서비스(listWorkCases)가 받는 이름과 같아야 한다 — keyword/status/from/to.
 *
 * 정렬 선택지는 두지 않는다. 목록 Query 계약(API_SPEC 'OWNER 근무 관리')은
 * keyword/status/from/to/page/size 뿐이고 정렬은 서버가 정한다(REQUIREMENTS WORK-002).
 * 지갑 거래(WALLET-004)만 sort 를 계약에 두고 있어 그쪽 시트와는 다르다.
 */
export function buildAttendanceFilterParams(draft = {}) {
  const f = { ...DEFAULT_FILTER, ...draft }
  const params = {}
  const keyword = String(f.keyword).trim()
  if (keyword) params.keyword = keyword
  if (f.status && f.status !== 'ALL') params.status = f.status
  if (f.from) params.from = f.from
  if (f.to) params.to = f.to
  return params
}
</script>

<script setup>
import { reactive, watch } from 'vue'

import AppField from '@/components/common/AppField.vue'
import BaseBottomSheet from '@/components/common/BaseBottomSheet.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import { WORK_CASE_STATUS_FILTER } from '@/constants/workCaseStatus'

const props = defineProps({
  open: { type: Boolean, default: false },
  // 현재 적용 중인 필터(시트를 다시 열 때 초안 복원용)
  modelValue: { type: Object, default: () => ({}) },
  /**
   * 기간 항목을 잠근다. 캘린더 뷰는 보고 있는 달로 조회 범위를 정하므로(monthRange)
   * 기간을 함께 받으면 두 범위가 충돌한다 — 그래서 캘린더에서는 이 값을 켠다.
   */
  dateRangeLocked: { type: Boolean, default: false }
})

const emit = defineEmits(['close', 'apply'])

const draft = reactive({ ...DEFAULT_FILTER })

// 시트가 열릴 때마다 현재 적용값으로 초안을 맞춘다.
watch(
  () => props.open,
  (open) => {
    if (open) Object.assign(draft, DEFAULT_FILTER, props.modelValue)
  }
)

function onReset() {
  Object.assign(draft, DEFAULT_FILTER)
}

function onApply() {
  emit('apply', buildAttendanceFilterParams(draft))
  emit('close')
}
</script>

<template>
  <BaseBottomSheet :open="open" title="검색·필터" @close="emit('close')">
    <div class="filter-body">
      <AppField v-model="draft.keyword" label="검색어" placeholder="근무 제목·알바생 검색" />

      <div class="group">
        <p class="group-label">유형</p>
        <div class="chips">
          <button
            v-for="opt in WORK_CASE_STATUS_FILTER"
            :key="opt.value"
            type="button"
            class="chip"
            :class="{ 'is-active': draft.status === opt.value }"
            @click="draft.status = opt.value"
          >
            {{ opt.label }}
          </button>
        </div>
      </div>

      <div class="group">
        <p class="group-label">기간</p>
        <div class="field-row">
          <AppField v-model="draft.from" type="date" :disabled="dateRangeLocked" />
          <span class="tilde">~</span>
          <AppField v-model="draft.to" type="date" :disabled="dateRangeLocked" />
        </div>
        <p v-if="dateRangeLocked" class="group-note">
          캘린더 보기에서는 달력에서 보고 있는 달로 기간이 정해져요.
        </p>
      </div>
    </div>

    <template #footer>
      <div class="actions">
        <BaseButton variant="secondary" class="reset" @click="onReset">초기화</BaseButton>
        <BaseButton variant="owner" class="apply" @click="onApply">적용</BaseButton>
      </div>
    </template>
  </BaseBottomSheet>
</template>

<style scoped>
.filter-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}
.group-label {
  margin-bottom: var(--space-sm);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-sub);
}
.group-note {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
}
.chip {
  padding: var(--space-xs) var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
  background: var(--color-surface);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.chip.is-active {
  border-color: var(--color-owner);
  background: var(--color-owner-weak);
  color: var(--color-owner);
  font-weight: var(--weight-medium);
}
.field-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}
.field-row > .field {
  flex: 1;
  min-width: 0;
}
.tilde {
  color: var(--color-text-sub);
}
.actions {
  display: flex;
  gap: var(--space-sm);
}
/* 초기화는 내용 폭으로 한 줄 유지, 적용이 남은 폭을 채우는 주 버튼. */
.reset {
  flex: 0 0 auto;
  white-space: nowrap;
}
.apply {
  flex: 1;
}
</style>
