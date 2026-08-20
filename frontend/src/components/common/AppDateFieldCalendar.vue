<script setup>
/**
 * 날짜 입력 필드 — 월 그리드 캘린더(CalendarGridPicker)로 값을 고른다.
 * 연/월/일 다이얼 버전과 비교해본 뒤 이 그리드 버전으로 확정했다(다이얼 버전은 삭제).
 * 라벨/에러/버튼/바텀시트 배치는 AppTimeField 와 같은 톤(FieldShell)을 맞춘다.
 */
import { ref } from 'vue'
import BaseBottomSheet from './BaseBottomSheet.vue'
import CalendarGridPicker from './CalendarGridPicker.vue'
import FieldShell from './FieldShell.vue'
import PickerSheetFooter from './PickerSheetFooter.vue'
import { formatDateKeyWithWeekday, todayKey } from '@/utils/calendar'

const props = defineProps({
  label: { type: String, default: '' },
  modelValue: { type: String, default: '' }, // "YYYY-MM-DD"
  error: { type: String, default: '' },
  hint: { type: String, default: '' },
  required: { type: Boolean, default: false },
  // 확인 버튼 톤 — 화면 역할(사장님/알바생)에 맞춘다.
  variant: { type: String, default: 'owner' }
})
const emit = defineEmits(['update:modelValue'])

const open = ref(false)
const draft = ref(todayKey())

function openSheet() {
  draft.value = props.modelValue || todayKey()
  open.value = true
}
function confirm() {
  emit('update:modelValue', draft.value)
  open.value = false
}
</script>

<template>
  <FieldShell
    v-slot="{ fieldId, describedBy, invalid }"
    :label="label"
    :error="error"
    :hint="hint"
    :required="required"
  >
    <button
      :id="fieldId"
      type="button"
      class="date-input"
      :class="{ placeholder: !modelValue }"
      :aria-describedby="describedBy"
      :aria-invalid="invalid"
      @click="openSheet"
    >
      {{ modelValue ? formatDateKeyWithWeekday(modelValue) : '날짜 선택' }}
    </button>
  </FieldShell>

  <BaseBottomSheet :open="open" :title="label || '날짜 선택'" @close="open = false">
    <CalendarGridPicker v-model="draft" />
    <template #footer>
      <PickerSheetFooter :variant="variant" @cancel="open = false" @confirm="confirm" />
    </template>
  </BaseBottomSheet>
</template>

<style scoped>
.date-input {
  width: 100%;
  text-align: left;
  padding: var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
}
.date-input.placeholder {
  color: var(--color-text-sub);
}
.field.has-error .date-input {
  border-color: var(--color-danger);
}
</style>
