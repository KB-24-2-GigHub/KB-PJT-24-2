<script setup>
/**
 * 연 · 월 · 일 세 컬럼으로 이루어진 날짜 다이얼 피커. TimeWheelPicker 와 같은 톤(휠 +
 * 중앙 강조선)을 쓴다 — 두 피커를 한 폼 안에서 같이 쓸 때 시각적으로 이질감이 없게 한다.
 *
 * v-model 은 항상 유효한 "YYYY-MM-DD" 문자열이어야 한다 — 비어 있으면 AppDateField 가
 * 시트를 열 때 기본값(오늘)을 채워서 넘긴다.
 */
import { computed } from 'vue'
import WheelColumn from './WheelColumn.vue'
import { WEEKDAY_LABELS } from '@/utils/calendar'

const props = defineProps({
  modelValue: { type: String, required: true }, // "YYYY-MM-DD"
  yearsBefore: { type: Number, default: 1 },
  yearsAfter: { type: Number, default: 2 }
})
const emit = defineEmits(['update:modelValue'])

function parse(value) {
  const [y, m, d] = value.split('-').map(Number)
  return { year: y, month: m, day: d }
}
const parsed = computed(() => parse(props.modelValue))

const thisYear = new Date().getFullYear()
const yearOptions = Array.from(
  { length: props.yearsBefore + props.yearsAfter + 1 },
  (_, i) => thisYear - props.yearsBefore + i
).map((y) => ({ label: `${y}년`, value: y }))
const monthOptions = Array.from({ length: 12 }, (_, i) => i + 1).map((m) => ({
  label: `${m}월`,
  value: m
}))
// 월마다 일수가 달라 연/월이 바뀔 때마다 다시 계산한다(2월 28/29일 등).
function daysInMonth(year, month) {
  return new Date(year, month, 0).getDate()
}
// 일 옆에 요일을 같이 보여준다 — 연/월이 바뀌면 같은 일(day)도 요일이 달라지므로
// year·month 를 함께 좇는 이 computed 안에서 매번 다시 구한다.
const dayOptions = computed(() => {
  const { year, month } = parsed.value
  return Array.from({ length: daysInMonth(year, month) }, (_, i) => i + 1).map((d) => {
    const weekday = WEEKDAY_LABELS[new Date(year, month - 1, d).getDay()]
    return { label: `${d}일 (${weekday})`, value: d }
  })
})

function emitWith(next) {
  const merged = { ...parsed.value, ...next }
  const maxDay = daysInMonth(merged.year, merged.month)
  const day = Math.min(merged.day, maxDay)
  const mm = String(merged.month).padStart(2, '0')
  const dd = String(day).padStart(2, '0')
  emit('update:modelValue', `${merged.year}-${mm}-${dd}`)
}
</script>

<template>
  <div class="wheel-picker">
    <WheelColumn
      :options="yearOptions"
      :model-value="parsed.year"
      @update:model-value="(v) => emitWith({ year: v })"
    />
    <WheelColumn
      :options="monthOptions"
      :model-value="parsed.month"
      @update:model-value="(v) => emitWith({ month: v })"
    />
    <WheelColumn
      :options="dayOptions"
      :model-value="parsed.day"
      @update:model-value="(v) => emitWith({ day: v })"
    />
    <div class="wheel-highlight" aria-hidden="true" />
  </div>
</template>

<style scoped>
.wheel-picker {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: 0 var(--space-md);
}
.wheel-highlight {
  position: absolute;
  left: var(--space-md);
  right: var(--space-md);
  top: 50%;
  height: 44px;
  transform: translateY(-50%);
  border-top: 1px solid var(--color-border);
  border-bottom: 1px solid var(--color-border);
  pointer-events: none;
}
</style>
