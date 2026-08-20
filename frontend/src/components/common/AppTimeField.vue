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
import { ref, watch } from 'vue'
import BaseBottomSheet from './BaseBottomSheet.vue'
import PickerSheetFooter from './PickerSheetFooter.vue'
import FieldShell from './FieldShell.vue'
import TimeWheelPicker from './TimeWheelPicker.vue'
import { roundTimeToStep, to12Hour } from '@/utils/timeWheel'

const AMPM_LABEL = { AM: '오전', PM: '오후' }

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

// 이 다이얼로는 애초에 10분 단위가 아닌 값을 만들 수 없다 — 그런데 이 필드가 받는
// modelValue 는 이 다이얼이 아니라 DB(과거 데이터·다른 경로로 등록된 값)에서 올 수도
// 있다. 받자마자 반올림해서 부모(form) 값 자체를 10분 단위로 맞춰버린다 — 그래야
// "화면에 보이는 값"과 "실제 저장되는 값"이 사용자가 필드를 건드리지 않아도 항상 같다.
watch(
  () => props.modelValue,
  (value) => {
    if (!value) return
    const rounded = roundTimeToStep(value, props.step)
    if (rounded !== value) emit('update:modelValue', rounded)
  },
  { immediate: true }
)

function formatDisplay(value) {
  if (!value) return ''
  const parsed = to12Hour(roundTimeToStep(value, props.step))
  if (!parsed) return ''
  return `${AMPM_LABEL[parsed.ampm]} ${String(parsed.hour12).padStart(2, '0')}:${String(parsed.minute).padStart(2, '0')}`
}

function openSheet() {
  // draft 도 반올림해서 연다 — 안 그러면 닫힌 필드/다이얼엔 반올림된 값(예: 09:20)이
  // 보이는데 아무것도 안 건드리고 확인을 눌러도 draft 에 남아있던 원본(09:23)이 그대로
  // emit 돼서, 사용자가 본 값과 실제로 저장되는 값이 달라진다.
  draft.value = roundTimeToStep(props.modelValue || '09:00', props.step)
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
      <PickerSheetFooter @cancel="open = false" @confirm="confirm" />
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
</style>
