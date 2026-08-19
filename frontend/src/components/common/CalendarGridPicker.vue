<script setup>
/**
 * 월 그리드 형태의 날짜 피커 — 다이얼(AppDateField) 대안으로 비교해보기 위한 버전.
 * 기존 브라우저 네이티브 달력과 같은 "월 그리드 + 요일 헤더" 형식은 유지하되, 동그란
 * 선택 표시·둥근 헤더 버튼 등으로 톤만 다이얼 쪽과 맞춘다.
 *
 * v-model 은 항상 유효한 "YYYY-MM-DD" 문자열이어야 한다 — 비어 있으면 AppDateFieldCalendar 가
 * 시트를 열 때 기본값(오늘)을 채워서 넘긴다.
 */
import { ChevronLeft, ChevronRight } from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'
import {
  WEEKDAY_LABELS,
  buildMonthGrid,
  formatMonthLabel,
  shiftMonth,
  toMonthKey,
  todayKey
} from '@/utils/calendar'

const props = defineProps({
  modelValue: { type: String, required: true } // "YYYY-MM-DD"
})
const emit = defineEmits(['update:modelValue'])

const today = todayKey()
const monthKey = ref(toMonthKey(props.modelValue))

// 시트를 다시 열어 다른 날짜로 시작할 수 있으니, 바깥에서 바뀐 달로도 따라간다.
watch(
  () => props.modelValue,
  (value) => {
    const nextMonth = toMonthKey(value)
    if (nextMonth !== monthKey.value) monthKey.value = nextMonth
  }
)

const monthLabel = computed(() => formatMonthLabel(monthKey.value))
const cells = computed(() => buildMonthGrid(monthKey.value))

function moveMonth(delta) {
  monthKey.value = shiftMonth(monthKey.value, delta)
}
function selectDate(dateKey) {
  emit('update:modelValue', dateKey)
}
</script>

<template>
  <div class="cal-picker">
    <header class="cal-head">
      <button type="button" class="cal-nav" aria-label="이전 달" @click="moveMonth(-1)">
        <ChevronLeft :size="18" />
      </button>
      <h3 class="cal-title" aria-live="polite">{{ monthLabel }}</h3>
      <button type="button" class="cal-nav" aria-label="다음 달" @click="moveMonth(1)">
        <ChevronRight :size="18" />
      </button>
    </header>

    <div class="cal-weekdays" aria-hidden="true">
      <span
        v-for="(label, index) in WEEKDAY_LABELS"
        :key="label"
        class="cal-weekday"
        :class="{ sun: index === 0, sat: index === 6 }"
      >
        {{ label }}
      </span>
    </div>

    <div class="cal-grid">
      <button
        v-for="cell in cells"
        :key="cell.dateKey"
        type="button"
        class="cal-cell"
        :class="{
          outside: !cell.inMonth,
          today: cell.dateKey === today,
          selected: cell.dateKey === modelValue,
          sun: cell.weekday === 0,
          sat: cell.weekday === 6
        }"
        :disabled="!cell.inMonth"
        :aria-pressed="cell.dateKey === modelValue"
        :aria-label="`${cell.dateKey}${cell.dateKey === today ? ', 오늘' : ''}`"
        @click="selectDate(cell.dateKey)"
      >
        {{ cell.day }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.cal-picker {
  padding: var(--space-sm) var(--space-md) var(--space-md);
}

.cal-head {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-lg);
  padding: var(--space-sm) 0;
}
.cal-nav {
  display: inline-flex;
  padding: var(--space-xs);
  border-radius: var(--radius-pill);
  color: var(--color-text-sub);
  transition: background 0.15s;
}
.cal-nav:active {
  background: var(--color-bg);
}
.cal-title {
  min-width: 8em;
  text-align: center;
  font-size: var(--text-lg);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}

.cal-weekdays,
.cal-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
}
.cal-weekdays {
  margin-top: var(--space-sm);
}
.cal-weekday {
  padding-bottom: var(--space-xs);
  text-align: center;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.cal-weekday.sun {
  color: var(--color-danger);
}
.cal-weekday.sat {
  color: var(--color-owner);
}

/* 동그란 선택 표시 — aspect-ratio 로 정사각형을 만들고 pill 반경으로 완전한 원을 만든다 */
.cal-cell {
  aspect-ratio: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 2px 0;
  border-radius: var(--radius-pill);
  font-size: var(--text-md);
  font-weight: var(--weight-medium);
  color: var(--color-text);
  transition:
    background 0.15s,
    color 0.15s;
}
.cal-cell.outside {
  color: var(--color-border);
  cursor: default;
}
.cal-cell.sun {
  color: var(--color-danger);
}
.cal-cell.sat {
  color: var(--color-owner);
}
.cal-cell.outside.sun,
.cal-cell.outside.sat {
  opacity: 0.4;
}
.cal-cell.today {
  box-shadow: inset 0 0 0 1px var(--color-owner);
}
.cal-cell.selected {
  background: var(--color-owner);
  color: var(--color-on-primary);
  font-weight: var(--weight-bold);
}
</style>
