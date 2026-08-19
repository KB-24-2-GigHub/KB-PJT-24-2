<script setup>
/**
 * 시각 입력 필드 — AppField 와 같은 라벨/에러/힌트 배치를 쓰되, 값은 네이티브
 * <input type="time"> 대신 바텀시트의 TimeWheelPicker 다이얼로 고른다.
 *
 * 네이티브 time input 은 안드로이드 Chrome 에서 `step` 을 무시하고 항상 1분 단위로
 * 스크롤돼서(iOS Safari 는 반대로 존중함) 분 단위 다이얼을 플랫폼 공통으로 보장할 수
 * 없었다 — 그래서 화면을 직접 그린다.
 *
 * v-model 은 AppField 와 동일하게 "HH:mm" 문자열이다.
 */
import { ref, useId } from 'vue'
import BaseBottomSheet from './BaseBottomSheet.vue'
import BaseButton from './BaseButton.vue'
import TimeWheelPicker from './TimeWheelPicker.vue'

const props = defineProps({
  label: { type: String, default: '' },
  modelValue: { type: String, default: '' }, // "HH:mm"
  step: { type: Number, default: 10 },
  error: { type: String, default: '' },
  hint: { type: String, default: '' },
  required: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const fieldId = useId()
const messageId = `${fieldId}-msg`
const open = ref(false)
const draft = ref('09:00')

function formatDisplay(value) {
  if (!value) return ''
  const [hStr, mStr] = value.split(':')
  const h = parseInt(hStr, 10)
  const ampm = h < 12 ? '오전' : '오후'
  const hour12 = h % 12 === 0 ? 12 : h % 12
  return `${ampm} ${String(hour12).padStart(2, '0')}:${mStr}`
}

function openSheet() {
  draft.value = props.modelValue || '09:00'
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
      class="time-input"
      :class="{ placeholder: !modelValue }"
      :aria-describedby="error || hint ? messageId : undefined"
      :aria-invalid="error ? 'true' : undefined"
      @click="openSheet"
    >
      {{ formatDisplay(modelValue) || '시간 선택' }}
    </button>

    <p v-if="error" :id="messageId" class="msg error" role="alert">{{ error }}</p>
    <p v-else-if="hint" :id="messageId" class="msg hint">{{ hint }}</p>

    <BaseBottomSheet :open="open" :title="label || '시간 선택'" @close="open = false">
      <TimeWheelPicker v-model="draft" :step="step" />
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
.time-input {
  width: 100%;
  text-align: left;
  padding: var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
}
.time-input.placeholder {
  color: var(--color-text-sub);
}
.field.has-error .time-input {
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
