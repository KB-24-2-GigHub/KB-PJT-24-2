<script>
/**
 * 송금상세(거래내역) 필터 바텀시트 — /owner/home 에서 사용.
 *
 * 필터는 프론트에서 목록을 재계산하지 않고, 서버 파라미터로만 전달한다
 * (서버가 최종 권위 — docs/rules/domain.md). buildTransactionFilterParams 는
 * 빈 값/기본값을 제외한 GET /wallet/transactions 파라미터를 만든다.
 */
export const DEFAULT_FILTER = {
  workplaceId: '',
  keyword: '',
  type: 'ALL',
  sort: 'LATEST',
  from: '',
  to: '',
  minAmount: '',
  maxAmount: ''
}

/** 필터 초안 → 서버 파라미터(빈 값·기본값 제외). 순수 함수라 단위 테스트 대상. */
export function buildTransactionFilterParams(draft = {}) {
  const f = { ...DEFAULT_FILTER, ...draft }
  const params = {}
  if (f.workplaceId !== '' && Number(f.workplaceId) > 0) {
    params.workplaceId = Number(f.workplaceId)
  }
  const keyword = String(f.keyword).trim()
  if (keyword) params.keyword = keyword
  if (f.type && f.type !== 'ALL') params.type = f.type
  if (f.sort) params.sort = f.sort
  if (f.from) params.from = f.from
  if (f.to) params.to = f.to
  if (f.minAmount !== '' && Number(f.minAmount) >= 0) params.minAmount = Number(f.minAmount)
  if (f.maxAmount !== '' && Number(f.maxAmount) >= 0) params.maxAmount = Number(f.maxAmount)
  if (Number.isInteger(Number(f.page)) && Number(f.page) >= 0) params.page = Number(f.page)
  if (Number.isInteger(Number(f.size)) && Number(f.size) > 0) params.size = Number(f.size)
  return params
}
</script>

<script setup>
import { ChevronDown } from 'lucide-vue-next'
import { computed, reactive, watch } from 'vue'

import AppField from '@/components/common/AppField.vue'
import BaseBottomSheet from '@/components/common/BaseBottomSheet.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import { TX_SORT, TX_TYPE_FILTER } from '@/utils/constants'
import { blockNonDigitKeydown } from '@/utils/format'

const props = defineProps({
  open: { type: Boolean, default: false },
  // 현재 적용 중인 필터(시트를 다시 열 때 초안 복원용)
  modelValue: { type: Object, default: () => ({}) },
  workplaces: { type: Array, default: () => [] }
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

// 금액 범위는 원 단위 정수 문자열로 보관하고, 화면에는 천단위 콤마로 표시한다.
const minAmountDisplay = computed(() => formatAmount(draft.minAmount))
const maxAmountDisplay = computed(() => formatAmount(draft.maxAmount))
function formatAmount(v) {
  return v === '' || v == null ? '' : Number(v).toLocaleString('ko-KR')
}
function onAmountInput(key, v) {
  draft[key] = String(v).replace(/[^\d]/g, '')
}

function onReset() {
  Object.assign(draft, DEFAULT_FILTER)
}

function onApply() {
  emit('apply', buildTransactionFilterParams(draft))
  emit('close')
}
</script>

<template>
  <BaseBottomSheet :open="open" title="검색·필터" @close="emit('close')">
    <div class="filter-body">
      <div v-if="workplaces.length" class="group">
        <label class="group-label" for="wallet-workplace-filter">사업장</label>
        <!-- 화살표는 AppTopBar 의 지점 select 와 같은 이유로 직접 그린다(#275). -->
        <span class="select-wrap">
          <select id="wallet-workplace-filter" v-model="draft.workplaceId" class="select">
            <option value="">전체 사업장</option>
            <option
              v-for="workplace in workplaces"
              :key="workplace.workplaceId"
              :value="workplace.workplaceId"
            >
              {{ workplace.name }}
            </option>
          </select>
          <ChevronDown :size="16" class="select-caret" aria-hidden="true" />
        </span>
      </div>

      <AppField v-model="draft.keyword" label="검색어" placeholder="내용·설명 검색" />

      <div class="group">
        <p class="group-label">유형</p>
        <div class="chips">
          <button
            v-for="opt in TX_TYPE_FILTER"
            :key="opt.value"
            type="button"
            class="chip"
            :class="{ 'is-active': draft.type === opt.value }"
            @click="draft.type = opt.value"
          >
            {{ opt.label }}
          </button>
        </div>
      </div>

      <div class="group">
        <p class="group-label">정렬</p>
        <div class="chips">
          <button
            v-for="opt in TX_SORT"
            :key="opt.value"
            type="button"
            class="chip"
            :class="{ 'is-active': draft.sort === opt.value }"
            @click="draft.sort = opt.value"
          >
            {{ opt.label }}
          </button>
        </div>
      </div>

      <div class="group">
        <p class="group-label">기간</p>
        <div class="field-row">
          <AppField v-model="draft.from" type="date" />
          <span class="tilde">~</span>
          <AppField v-model="draft.to" type="date" />
        </div>
      </div>

      <div class="group">
        <p class="group-label">금액 범위</p>
        <div class="field-row">
          <AppField
            :model-value="minAmountDisplay"
            type="tel"
            placeholder="최소"
            @keydown="blockNonDigitKeydown"
            @update:model-value="(v) => onAmountInput('minAmount', v)"
          />
          <span class="tilde">~</span>
          <AppField
            :model-value="maxAmountDisplay"
            type="tel"
            placeholder="최대"
            @keydown="blockNonDigitKeydown"
            @update:model-value="(v) => onAmountInput('maxAmount', v)"
          />
        </div>
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
  display: block;
  margin-bottom: var(--space-sm);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-sub);
}
/* 네이티브 화살표는 padding box 안에 UA 가 그려 위치를 제어할 수 없고, 긴 사업장명이
   그 밑으로 파고든다. 화살표를 끄고 직접 그려 자리를 확실히 비운다(#275). */
.select-wrap {
  position: relative;
  display: flex;
  align-items: center;
}
.select {
  width: 100%;
  padding: var(--space-md);
  /* caret(16px) + 좌우 여백 */
  padding-right: 36px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  color: var(--color-text);
  appearance: none;
  -webkit-appearance: none;
  -moz-appearance: none;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.select-caret {
  position: absolute;
  right: var(--space-md);
  color: var(--color-text-sub);
  /* 화살표를 눌러도 select 가 열려야 한다. */
  pointer-events: none;
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
