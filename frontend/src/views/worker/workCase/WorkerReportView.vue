<script setup>
/** WORKER가 분쟁을 접수하고 같은 화면에서 저장된 DEMO 검토 상태를 확인합니다. */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import AppBackHeader from '@/components/common/AppBackHeader.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import DisputeTimeline from '@/components/dispute/DisputeTimeline.vue'
import { createReport, listReports } from '@/services/workCases'
import { useUiStore } from '@/stores/ui'

const TITLE_MAX_LENGTH = 100
const CONTENT_MAX_LENGTH = 2000

const route = useRoute()
const ui = useUiStore()
const workCaseId = route.params.workCaseId

const title = ref('')
const content = ref('')
const reports = ref([])
const loadingReports = ref(false)
const reportsLoadError = ref(false)
const disputeUnavailable = ref(false)
const reviewUnavailable = ref(false)
const submitting = ref(false)

const titleLength = computed(() => title.value.trim().length)
const contentLength = computed(() => content.value.trim().length)
const hasOpenReport = computed(() =>
  reports.value.some((report) => ['OPEN', 'UNDER_REVIEW'].includes(report.status))
)
const canSubmit = computed(
  () =>
    titleLength.value >= 1 &&
    titleLength.value <= TITLE_MAX_LENGTH &&
    contentLength.value >= 1 &&
    contentLength.value <= CONTENT_MAX_LENGTH &&
    !hasOpenReport.value &&
    !disputeUnavailable.value &&
    !reviewUnavailable.value &&
    !submitting.value
)

async function loadReports({ notify = false } = {}) {
  loadingReports.value = true
  try {
    const page = await listReports(workCaseId)
    reports.value = page.content ?? []
    reportsLoadError.value = false
    disputeUnavailable.value = false
  } catch (error) {
    reportsLoadError.value = true
    disputeUnavailable.value = error?.code === 'CONFLICT'
    if (notify) ui.toast('분쟁 상태를 불러오지 못했습니다.', { type: 'warning' })
  } finally {
    loadingReports.value = false
  }
}

async function onSubmit() {
  if (!canSubmit.value) {
    ui.toast('제목과 경위를 입력 범위에 맞게 작성해주세요.', { type: 'warning' })
    return
  }

  submitting.value = true
  try {
    await createReport(workCaseId, {
      title: title.value.trim(),
      content: content.value.trim()
    })
    title.value = ''
    content.value = ''
    ui.toast('분쟁이 접수되어 예치금 흐름을 확인하고 있습니다.', { type: 'success' })
    await loadReports()
  } catch (error) {
    const duplicate = error?.code === 'DISPUTE_ALREADY_OPEN'
    const unavailable = error?.code === 'CONFLICT'
    const reviewUnavailableError = error?.code === 'DISPUTE_REVIEW_UNAVAILABLE'
    if (unavailable) {
      disputeUnavailable.value = true
      reportsLoadError.value = true
    }
    if (reviewUnavailableError) reviewUnavailable.value = true
    ui.toast(
      duplicate
        ? '이미 처리 중인 분쟁이 있습니다. 아래 상태를 확인해주세요.'
        : unavailable
          ? '현재 근무 상태에서는 분쟁을 접수할 수 없습니다.'
          : reviewUnavailableError
            ? '분쟁 검토 DEMO가 비활성화되어 있습니다.'
            : '신고 접수에 실패했습니다. 잠시 후 다시 시도해주세요.',
      { type: duplicate || unavailable || reviewUnavailableError ? 'warning' : 'danger' }
    )
    if (duplicate) await loadReports()
  } finally {
    submitting.value = false
  }
}

onMounted(loadReports)
</script>

<template>
  <div class="sub-page">
    <AppBackHeader title="임금분쟁 DEMO" />
    <main class="screen-body">
      <p class="notice">
        접수 중에는 GigHub가 예치금 흐름을 일시 보류합니다. AI 검토는 외부 조정 시스템을 흉내 내는
        DEMO이며 법적 판단이나 자문이 아닙니다.
      </p>

      <DisputeTimeline
        :reports="reports"
        :loading="loadingReports"
        :error="reportsLoadError"
        :error-message="
          disputeUnavailable ? '현재 근무 상태에서는 분쟁을 신고하거나 조회할 수 없어요.' : ''
        "
        @refresh="loadReports({ notify: true })"
      />

      <section class="form-card">
        <h2>새 분쟁 접수</h2>
        <p v-if="hasOpenReport" class="open-guide">
          처리 중인 분쟁이 있어 새 신고는 접수할 수 없습니다.
        </p>
        <p v-else-if="reviewUnavailable" class="open-guide">
          분쟁 검토 DEMO가 비활성화되어 새 분쟁을 접수할 수 없습니다.
        </p>
        <p v-else-if="disputeUnavailable" class="open-guide">
          현재 근무 상태에서는 새 분쟁을 접수할 수 없습니다.
        </p>

        <label class="field">
          <span class="label">제목</span>
          <input
            v-model="title"
            class="input"
            :maxlength="TITLE_MAX_LENGTH"
            placeholder="예: 약정 일급 지급 확인 요청"
          />
          <span class="counter">{{ titleLength }}/{{ TITLE_MAX_LENGTH }}자</span>
        </label>

        <label class="field">
          <span class="label">경위</span>
          <textarea
            v-model="content"
            class="textarea"
            rows="8"
            :maxlength="CONTENT_MAX_LENGTH"
            placeholder="언제, 어떤 임금 문제가 있었는지 작성해주세요."
          ></textarea>
          <span class="counter">{{ contentLength }}/{{ CONTENT_MAX_LENGTH }}자</span>
        </label>

        <BaseButton
          class="submit"
          variant="danger"
          size="lg"
          block
          :disabled="!canSubmit"
          @click="onSubmit"
        >
          {{ submitting ? '접수 중…' : '분쟁 접수' }}
        </BaseButton>
      </section>
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
.notice,
.open-guide {
  padding: var(--space-md);
  border-radius: var(--radius-md);
  background: var(--color-bg);
  color: var(--color-text-sub);
  font-size: var(--text-sm);
  line-height: 1.5;
}
.form-card {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
  padding: var(--space-lg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-surface);
}
.form-card h2 {
  font-size: var(--text-lg);
  font-weight: var(--weight-bold);
}
.field {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}
.label {
  color: var(--color-text-sub);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
}
.input,
.textarea {
  width: 100%;
  padding: var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
}
.textarea {
  resize: vertical;
  line-height: 1.6;
}
.input:focus,
.textarea:focus {
  outline: none;
  border-color: var(--color-primary);
}
.counter {
  align-self: flex-end;
  color: var(--color-text-sub);
  font-size: var(--text-sm);
}
</style>
