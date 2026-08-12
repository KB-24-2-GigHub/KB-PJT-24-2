<script setup>
/**
 * [F] 임금분쟁 신고  ·  /worker/work/work-cases/:workCaseId/report  ·  WORKER(본인 근무)
 * 경위서 작성·제출. 기록·알림용 — 정산 영향 없음. 제출 시 사장 알림(WAGE_REPORTED).
 * 연계 API: POST /work-cases/{id}/disputes  →  @/services/workCases (createReport)
 * route.params.workCaseId 사용. 공통: BaseButton · 제출 후 useUiStore().toast + 뒤로가기.
 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import AppBackHeader from '@/components/common/AppBackHeader.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import { createReport } from '@/services/workCases'
import { useUiStore } from '@/stores/ui'

const MIN_LENGTH = 10

const route = useRoute()
const router = useRouter()
const ui = useUiStore()

const workCaseId = route.params.workCaseId
const content = ref('')
const submitting = ref(false)

const trimmedLength = computed(() => content.value.trim().length)
const canSubmit = computed(() => trimmedLength.value >= MIN_LENGTH && !submitting.value)

async function onSubmit() {
  if (!canSubmit.value) {
    ui.toast(`경위서를 ${MIN_LENGTH}자 이상 작성해주세요.`, { type: 'warning' })
    return
  }

  submitting.value = true
  try {
    await createReport(workCaseId, { content: content.value.trim() })
    ui.toast('신고가 접수되었습니다.', { type: 'success' })
    router.back()
  } catch (error) {
    const unavailable = error?.code === 'FEATURE_UNAVAILABLE'
    ui.toast(
      unavailable
        ? '임금분쟁 신고는 현재 준비 중인 기능입니다.'
        : '신고 접수에 실패했습니다. 잠시 후 다시 시도해주세요.',
      { type: unavailable ? 'info' : 'danger' }
    )
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="sub-page">
    <AppBackHeader title="임금분쟁 신고" />
    <main class="screen-body">
      <p class="notice">
        신고 절차에는 시간이 소요될 수 있어요 · 신고 전에 먼저 사장님과 연락해보는 것을 권장드려요
      </p>

      <label class="field">
        <span class="label">경위서</span>
        <textarea
          v-model="content"
          class="textarea"
          rows="10"
          placeholder="언제, 어떤 임금 문제가 있었는지 구체적으로 작성해주세요."
        ></textarea>
        <span class="counter">{{ trimmedLength }}자 (최소 {{ MIN_LENGTH }}자)</span>
      </label>

      <BaseButton
        class="submit"
        variant="danger"
        size="lg"
        block
        :disabled="!canSubmit"
        @click="onSubmit"
      >
        {{ submitting ? '접수 중…' : '신고 제출' }}
      </BaseButton>
    </main>
  </div>
</template>

<style scoped>
.screen-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
  padding: var(--space-lg);
}
.notice {
  padding: var(--space-md);
  background: var(--color-bg);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
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
.textarea {
  width: 100%;
  padding: var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  resize: vertical;
  line-height: 1.6;
}
.textarea:focus {
  outline: none;
  border-color: var(--color-primary);
}
.counter {
  align-self: flex-end;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.submit {
  margin-top: var(--space-sm);
}
</style>
