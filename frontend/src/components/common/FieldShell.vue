<script setup>
/**
 * 라벨 + 컨트롤 + 에러/성공/힌트 배치 — AppField·AppTimeField·AppDateFieldCalendar 가
 * 공유하는 뼈대. 컨트롤 자체(입력창/버튼)는 각 필드가 default 슬롯에 채운다.
 *
 * 슬롯 스코프로 fieldId·describedBy·invalid 를 내려준다 — 컨트롤이 라벨과 연결되고
 * (aria-describedby) 스크린리더가 에러/힌트를 같이 읽도록 각자 그대로 바인딩하면 된다.
 *   <FieldShell :label="label" :error="error" v-slot="{ fieldId, describedBy, invalid }">
 *     <input :id="fieldId" :aria-describedby="describedBy" :aria-invalid="invalid" ... />
 *   </FieldShell>
 */
import { computed, useId } from 'vue'

const props = defineProps({
  label: { type: String, default: '' },
  error: { type: String, default: '' },
  success: { type: String, default: '' },
  hint: { type: String, default: '' },
  required: { type: Boolean, default: false }
})

const fieldId = useId()
const messageId = `${fieldId}-msg`
// 최초 렌더 이후에 생기는 검증 오류·동적 hint도 aria-describedby 에 반영돼야 하므로
// computed 로 둔다 — setup 시점 한 번만 읽으면 나중에 생긴 메시지가 스크린리더에
// 연결되지 않는다.
const hasMessage = computed(() => props.error || props.success || props.hint)
</script>

<template>
  <div class="field" :class="{ 'has-error': error }">
    <label v-if="label" :for="fieldId" class="label">
      {{ label }}
      <span v-if="required" class="req" aria-hidden="true">*</span>
    </label>

    <slot
      :field-id="fieldId"
      :message-id="messageId"
      :described-by="hasMessage ? messageId : undefined"
      :invalid="error ? 'true' : undefined"
    />

    <p v-if="error" :id="messageId" class="msg error" role="alert">{{ error }}</p>
    <p v-else-if="success" :id="messageId" class="msg success">{{ success }}</p>
    <p v-else-if="hint" :id="messageId" class="msg hint">{{ hint }}</p>
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
.msg {
  font-size: var(--text-sm);
}
.msg.error {
  color: var(--color-danger);
}
.msg.success {
  color: var(--color-success);
}
.msg.hint {
  color: var(--color-text-sub);
}
</style>
