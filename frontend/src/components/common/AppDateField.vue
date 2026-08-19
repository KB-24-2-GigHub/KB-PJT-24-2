<script setup>
/**
 * 날짜 입력 필드 — AppTimeField 와 같은 톤(라벨 + 값 버튼 + 바텀시트 다이얼).
 * v-model 은 AppField 의 type="date" 와 동일한 "YYYY-MM-DD" 문자열이다.
 */
import { ref, useId } from 'vue'
import BaseBottomSheet from './BaseBottomSheet.vue'
import BaseButton from './BaseButton.vue'
import DateWheelPicker from './DateWheelPicker.vue'
import { formatDateKeyWithWeekday, todayKey } from '@/utils/calendar'

const props = defineProps({
  label: { type: String, default: '' },
  modelValue: { type: String, default: '' }, // "YYYY-MM-DD"
  error: { type: String, default: '' },
  hint: { type: String, default: '' },
  required: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const fieldId = useId()
const messageId = `${fieldId}-msg`
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
  <div class="field" :class="{ 'has-error': error }">
    <label v-if="label" :for="fieldId" class="label">
      {{ label }}
      <span v-if="required" class="req" aria-hidden="true">*</span>
    </label>

    <button
      :id="fieldId"
      type="button"
      class="date-input"
      :class="{ placeholder: !modelValue }"
      :aria-describedby="error || hint ? messageId : undefined"
      :aria-invalid="error ? 'true' : undefined"
      @click="openSheet"
    >
      {{ modelValue ? formatDateKeyWithWeekday(modelValue) : '날짜 선택' }}
    </button>

    <p v-if="error" :id="messageId" class="msg error" role="alert">{{ error }}</p>
    <p v-else-if="hint" :id="messageId" class="msg hint">{{ hint }}</p>

    <BaseBottomSheet :open="open" :title="label || '날짜 선택'" @close="open = false">
      <DateWheelPicker v-model="draft" />
      <template #footer>
        <div class="sheet-actions">
          <BaseButton variant="secondary" block @click="open = false">취소</BaseButton>
          <BaseButton variant="owner" block @click="confirm">확인</BaseButton>
        </div>
      </template>
    </BaseBottomSheet>
  </div>
</template>

<style scoped>
.field {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}
.label {
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-sub);
}
.req {
  color: var(--color-danger);
}
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
.msg {
  font-size: var(--text-sm);
}
.msg.error {
  color: var(--color-danger);
}
.msg.hint {
  color: var(--color-text-sub);
}
.sheet-actions {
  display: flex;
  gap: var(--space-sm);
}
</style>
