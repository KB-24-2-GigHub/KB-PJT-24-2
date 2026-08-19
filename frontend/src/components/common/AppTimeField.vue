<script setup>
/**
 * 시각 입력 필드 — AppField 와 같은 라벨/에러/힌트 배치(FieldShell)를 쓰되, 값은 네이티브
 * <input type="time"> 대신 바텀시트의 TimeWheelPicker 다이얼로 고른다.
 *
 * 네이티브 time input 은 안드로이드 Chrome 에서 `step` 을 무시하고 항상 1분 단위로
 * 스크롤돼서(iOS Safari 는 반대로 존중함) 분 단위 다이얼을 플랫폼 공통으로 보장할 수
 * 없었다 — 그래서 화면을 직접 그린다.
 *
 * v-model 은 AppField 와 동일하게 "HH:mm" 문자열이다.
 */
import { ref } from 'vue'
import BaseBottomSheet from './BaseBottomSheet.vue'
import BaseButton from './BaseButton.vue'
import FieldShell from './FieldShell.vue'
import TimeWheelPicker from './TimeWheelPicker.vue'
import { roundTimeToStep } from '@/utils/timeWheel'

const props = defineProps({
  label: { type: String, default: '' },
  modelValue: { type: String, default: '' }, // "HH:mm"
  step: { type: Number, default: 10 },
  error: { type: String, default: '' },
  hint: { type: String, default: '' },
  required: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const open = ref(false)
const draft = ref('09:00')

// 다이얼(TimeWheelPicker)과 같은 반올림을 거쳐야 한다 — 안 그러면 닫힌 필드에는
// 원래 값이 그대로 보이는데 시트를 열고 확인만 눌러도 dial 이 반올림한 값으로
// 바뀌어 버린다(둘이 서로 다른 값을 "지금 값"이라고 보여주는 상태였다).
function formatDisplay(value) {
  if (!value) return ''
  const [hStr, mStr] = roundTimeToStep(value, props.step).split(':')
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
      class="time-input"
      :class="{ placeholder: !modelValue }"
      :aria-describedby="describedBy"
      :aria-invalid="invalid"
      @click="openSheet"
    >
      {{ formatDisplay(modelValue) || '시간 선택' }}
    </button>
  </FieldShell>

  <BaseBottomSheet :open="open" :title="label || '시간 선택'" @close="open = false">
    <TimeWheelPicker v-model="draft" :step="step" />
    <template #footer>
      <div class="sheet-actions">
        <BaseButton variant="secondary" block @click="open = false">취소</BaseButton>
        <BaseButton variant="owner" block @click="confirm">확인</BaseButton>
      </div>
    </template>
  </BaseBottomSheet>
</template>

<style scoped>
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
.sheet-actions {
  display: flex;
  gap: var(--space-sm);
}
</style>
