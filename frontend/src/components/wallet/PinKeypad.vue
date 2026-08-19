<script setup>
/**
 * PIN 입력 보안키패드 — 계좌 PIN·지갑 비밀번호 공용.
 * 기기 키보드 대신 화면에 무작위로 배치된 숫자판을 눌러 입력한다. 내부 input 은
 * readonly 라 기기 키보드가 뜨지 않고 사용자 입력으로 값이 바뀌지 않는다 — 실제
 * 입력은 오직 아래 숫자 버튼(press/backspace)으로만 반영된다. 이 input 자체는
 * `@/test-utils/pinKeypad`(typePin) 도입 전 테스트가 값을 읽던 자리이자 현재값을
 * 화면 밖에서 조회하는 용도로만 남겨뒀다 — `aria-hidden`이라 접근성 트리에는 없다.
 *
 * 숫자 배열은 마운트 시 한 번 섞고, 부모가 PIN 을 비울 때마다(오입력·제출 후 폐기)
 * 다시 섞는다 — 매번 같은 자리를 눌러 어깨너머로 자리만 외우는 것을 막는다.
 */
import { ref, watch } from 'vue'
import { Delete } from 'lucide-vue-next'

const props = defineProps({
  modelValue: { type: String, default: '' },
  label: { type: String, default: '' },
  length: { type: Number, default: 4 },
  error: { type: String, default: '' },
  hint: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue'])

function shuffledDigits() {
  const result = ['0', '1', '2', '3', '4', '5', '6', '7', '8', '9']
  for (let i = result.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1))
    ;[result[i], result[j]] = [result[j], result[i]]
  }
  return result
}

const digits = ref(shuffledDigits())
const inputEl = ref(null)

watch(
  () => props.modelValue,
  (next, prev) => {
    // 값이 지워졌다가(오입력 초기화·제출 완료) 다시 시작될 때만 숫자 배치를 재배치한다.
    if (next === '' && prev !== '') digits.value = shuffledDigits()

    // AppField 와 같은 이유: 부모가 modelValue 를 프로그램적으로 바꿀 때 실제 DOM 값도
    // 맞춰준다(스크립트로 값을 바꾼 뒤 Vue 가 같은 문자열로 패치를 건너뛰는 경우 대비).
    const el = inputEl.value
    if (el && el.value !== next) el.value = next
  }
)

function press(digit) {
  if (props.modelValue.length >= props.length) return
  emit('update:modelValue', props.modelValue + digit)
}

function backspace() {
  if (!props.modelValue) return
  emit('update:modelValue', props.modelValue.slice(0, -1))
}
</script>

<template>
  <div class="pin-keypad" :class="{ 'has-error': error }">
    <label v-if="label" class="label">{{ label }}</label>

    <div class="dots" role="status" :aria-label="`PIN ${modelValue.length}자리 입력됨`">
      <span v-for="i in length" :key="i" class="dot" :class="{ filled: i <= modelValue.length }" />
    </div>

    <!-- readonly: 기기 키보드를 막아 보안키패드만으로 입력하게 한다 — 사용자 입력으로는
         절대 값이 바뀌지 않으므로 @input 핸들러를 두지 않는다(달아도 도달 불가한 죽은
         코드가 된다). 화면에는 보이지 않고 aria-hidden 이라 접근성 트리에도 없다 —
         현재값을 스크립트로 조회하는 용도로만 DOM 에 남긴다. -->
    <input
      ref="inputEl"
      class="hidden-input"
      type="password"
      inputmode="none"
      autocomplete="off"
      readonly
      tabindex="-1"
      aria-hidden="true"
      :value="modelValue"
    />

    <div class="pad">
      <button v-for="d in digits" :key="d" type="button" class="key" @click="press(d)">
        {{ d }}
      </button>
      <span class="key key--blank" aria-hidden="true"></span>
      <button type="button" class="key key--action" aria-label="한 자리 지우기" @click="backspace">
        <Delete :size="20" />
      </button>
    </div>

    <p v-if="error" class="msg error" role="alert">{{ error }}</p>
    <p v-else-if="hint" class="msg hint">{{ hint }}</p>
  </div>
</template>

<style scoped>
.pin-keypad {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}
.label {
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-sub);
}
.dots {
  display: flex;
  justify-content: center;
  gap: var(--space-md);
  padding: var(--space-sm) 0;
}
.dot {
  width: 14px;
  height: 14px;
  border-radius: var(--radius-pill);
  border: 1px solid var(--color-border);
  background: var(--color-surface);
}
.dot.filled {
  background: var(--color-text);
  border-color: var(--color-text);
}
.has-error .dot {
  border-color: var(--color-danger);
}
/* 화면에는 보이지 않지만 접근성 트리·테스트 자동화에서는 조회할 수 있게 남긴다. */
.hidden-input {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
.pad {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: var(--space-sm);
}
.key {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 52px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  font-size: var(--text-lg);
  font-weight: var(--weight-medium);
  color: var(--color-text);
  transition: background-color 0.1s ease;
}
.key:active {
  background: var(--color-bg);
}
.key--blank {
  border-color: transparent;
  background: transparent;
}
.key--action {
  color: var(--color-text-sub);
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
</style>
