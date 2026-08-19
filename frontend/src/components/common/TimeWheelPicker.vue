<script setup>
/**
 * 오전/오후 · 시 · 분 세 컬럼으로 이루어진 시각 다이얼 피커.
 * v-model 은 항상 유효한 "HH:mm"(24시간제) 문자열이어야 한다 — 비어 있으면 AppTimeField 가
 * 시트를 열 때 기본값을 채워서 넘긴다.
 *
 * 분은 `step` 분 단위로만 고를 수 있다(기본 10분) — 알바 시프트를 1분 단위로 맞출 일은
 * 거의 없어서, 분 단위 다이얼로 화면 스와이프 횟수를 10배 줄인다.
 */
import { computed } from 'vue'
import WheelColumn from './WheelColumn.vue'
import { roundTimeToStep } from '@/utils/timeWheel'

const props = defineProps({
  modelValue: { type: String, required: true }, // "HH:mm"
  step: { type: Number, default: 10 }
})
const emit = defineEmits(['update:modelValue'])

const AMPM_OPTIONS = [
  { label: '오전', value: 'AM' },
  { label: '오후', value: 'PM' }
]
const HOUR_OPTIONS = Array.from({ length: 12 }, (_, i) => i + 1).map((h) => ({
  label: String(h).padStart(2, '0'),
  value: h
}))
const minuteOptions = computed(() =>
  Array.from({ length: Math.ceil(60 / props.step) }, (_, i) => i * props.step).map((m) => ({
    label: String(m).padStart(2, '0'),
    value: m
  }))
)

function parse(value) {
  // 반올림을 총 분(hour*60+minute) 단위로 먼저 끝내 55분 같은 값이 60분이 아니라
  // 다음 시(hour)로 자리올림되게 한다 — 분만 따로 반올림하면 이 자리올림이 사라진다.
  const [hStr, mStr] = roundTimeToStep(value, props.step).split(':')
  const h = parseInt(hStr, 10)
  const ampm = h < 12 ? 'AM' : 'PM'
  const hour12 = h % 12 === 0 ? 12 : h % 12
  return { ampm, hour12, minute: parseInt(mStr, 10) }
}

const parsed = computed(() => parse(props.modelValue))

function emitWith(next) {
  const merged = { ...parsed.value, ...next }
  let h = merged.hour12 % 12
  if (merged.ampm === 'PM') h += 12
  const hh = String(h).padStart(2, '0')
  const mm = String(merged.minute).padStart(2, '0')
  emit('update:modelValue', `${hh}:${mm}`)
}
</script>

<template>
  <div class="wheel-picker">
    <WheelColumn
      :options="AMPM_OPTIONS"
      :model-value="parsed.ampm"
      @update:model-value="(v) => emitWith({ ampm: v })"
    />
    <WheelColumn
      :options="HOUR_OPTIONS"
      :model-value="parsed.hour12"
      @update:model-value="(v) => emitWith({ hour12: v })"
    />
    <span class="colon">:</span>
    <WheelColumn
      :options="minuteOptions"
      :model-value="parsed.minute"
      @update:model-value="(v) => emitWith({ minute: v })"
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
.colon {
  font-size: var(--text-xl);
  font-weight: var(--weight-bold);
  color: var(--color-text);
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
