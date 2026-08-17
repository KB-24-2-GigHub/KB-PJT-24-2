<script setup>
/**
 * 근태관리 뷰 전환 토글 — 목록형 ↔ 캘린더.
 *
 * 화면(OwnerAttendanceView)이 어떤 뷰를 그릴지만 고르는 순수 표시 컴포넌트다.
 * 조회·필터·저장은 모두 부모가 맡는다(여기서는 상태를 갖지 않는다).
 *
 * 사용: <AttendanceViewToggle v-model="viewMode" />   // 'list' | 'calendar'
 *
 * 접근성: 두 버튼을 라디오 그룹처럼 다루고, 선택된 쪽에 aria-pressed 를 준다.
 */

//  CalenderDays: 달력 아이콘, List: 목록 아이콘
import { CalendarDays, List } from 'lucide-vue-next'

// 부모로부터 값을 modelValue라는 이름으로 전달받음  <AttendanceViewToggle v-model="viewMode" />
defineProps({
  /** 현재 뷰 모드. 'list'(목록형) | 'calendar'(캘린더) */
  modelValue: { type: String, default: 'list' }
})

// 부모에게 값이 바뀌었음을 알려주는 함수
const emit = defineEmits(['update:modelValue'])

// 토글 버튼 정보를 배열로 저장
// 라벨·아이콘을 배열로 두고 v-for 로 그린다 — 버튼 마크업 중복을 없앤다.
const MODES = [
  { value: 'list', label: '목록형', icon: List },
  { value: 'calendar', label: '캘린더', icon: CalendarDays }
]
</script>

<template>
  <div class="view-toggle" role="group" aria-label="근태 보기 방식">
    <button
      v-for="mode in MODES"
      :key="mode.value"
      type="button"
      class="view-toggle-btn"
      :class="{ active: modelValue === mode.value }"
      :aria-pressed="modelValue === mode.value"
      @click="emit('update:modelValue', mode.value)"
    >
      <component :is="mode.icon" :size="15" />
      {{ mode.label }}
    </button>
  </div>
</template>

<style scoped>
/* 세그먼트 컨트롤 — 알약 모양 트랙 안에서 선택된 칸만 흰 카드로 떠 보이게 한다 */
.view-toggle {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-xs);
  padding: var(--space-xs);
  background: var(--color-bg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-pill);
}
.view-toggle-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  padding: var(--space-sm);
  border-radius: var(--radius-pill);
  font-size: var(--text-md);
  font-weight: var(--weight-medium);
  color: var(--color-text-sub);
}
.view-toggle-btn.active {
  background: var(--color-surface);
  color: var(--color-owner);
  box-shadow: var(--shadow-card);
}
</style>
