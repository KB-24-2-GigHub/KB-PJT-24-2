<script setup>
/**
 * [C] 근무 포지션 등록  ·  /owner/attendance/work-cases/new  ·  OWNER
 * 제목·날짜·시작/종료시간·휴게시간(유급/무급)·일급 입력 → status=DRAFT 생성.
 * 지점 컨텍스트: useWorkplaceStore().selectedId.
 * 연계 API: POST /workplaces/{id}/work-cases  →  @/services/workCases (createWorkCase)
 * 공통: AppField · AppTimeField · BaseButton · @/utils/validators
 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import AppBackHeader from '@/components/common/AppBackHeader.vue'
import AppField from '@/components/common/AppField.vue'
import AppTimeField from '@/components/common/AppTimeField.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import { fieldErrorMap } from '@/services/http'
import { createWorkCase } from '@/services/workCases'
import { useUiStore } from '@/stores/ui'
import { useWorkplaceStore } from '@/stores/workplace'
import { formatKRW } from '@/utils/format'
import { breakMinutesRule, isPositiveAmount, isRequired, workPeriodRule } from '@/utils/validators'

const router = useRouter()
const ui = useUiStore()
const workplaceStore = useWorkplaceStore()

const form = reactive({
  title: '',
  workDate: '',
  startTime: '',
  endTime: '',
  breakMinutes: '',
  breakPaid: false,
  dailyWage: ''
})

const errors = reactive({
  title: '',
  workDate: '',
  startTime: '',
  endTime: '',
  breakMinutes: '',
  dailyWage: ''
})

const submitting = ref(false)

onMounted(() => workplaceStore.load())

function validate() {
  errors.title = isRequired(form.title, '제목').message
  errors.workDate = isRequired(form.workDate, '근무 날짜').message
  errors.startTime = isRequired(form.startTime, '시작시간').message
  // 종료가 시작보다 이르면 자정 넘김 근무다(SPEC-413-01). 순서 대신 길이 상한으로 거른다.
  errors.endTime = workPeriodRule(form.startTime, form.endTime).message
  // 휴게가 근무 길이를 넘는 것도 서버가 400 으로 거절한다 — 같은 경계를 여기서 먼저 본다.
  errors.breakMinutes = breakMinutesRule(form.startTime, form.endTime, form.breakMinutes).message
  errors.dailyWage = isPositiveAmount(form.dailyWage).message

  return Object.values(errors).every((message) => message === '')
}

async function onSubmit() {
  if (!validate()) {
    ui.toast('입력값을 다시 확인해주세요.', { type: 'warning' })
    return
  }

  const workplaceId = workplaceStore.selectedId
  if (workplaceId == null) {
    ui.toast('먼저 사업장을 선택해주세요.', { type: 'warning' })
    return
  }

  submitting.value = true
  try {
    await createWorkCase(workplaceId, {
      title: form.title.trim(),
      workDate: form.workDate,
      startTime: form.startTime,
      endTime: form.endTime,
      breakMinutes: Number(form.breakMinutes || 0),
      breakPaid: form.breakPaid,
      dailyWage: Number(form.dailyWage)
    })
    ui.toast('근무 포지션을 등록했어요.', { type: 'success' })
    router.push('/owner/attendance')
  } catch (err) {
    // 서버 fieldErrors는 폼 필드명과 같은 이름(title/workDate/startTime/endTime/
    // breakMinutes/dailyWage)을 쓴다 — 필드별 사유가 있으면 그 필드에, 없으면 토스트로.
    const serverErrors = fieldErrorMap(err)
    if (Object.keys(serverErrors).length > 0) {
      Object.assign(errors, serverErrors)
    } else {
      ui.toast('근무 등록에 실패했어요.', { type: 'danger' })
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="sub-page">
    <AppBackHeader title="근무 포지션 추가" />
    <main class="screen-body">
      <form class="form" @submit.prevent="onSubmit">
        <AppField
          v-model="form.title"
          label="제목"
          placeholder="예) 주말 홀 서빙"
          required
          :error="errors.title"
        />

        <AppField
          v-model="form.workDate"
          type="date"
          label="근무 날짜"
          required
          :error="errors.workDate"
        />

        <div class="field-row">
          <AppTimeField
            v-model="form.startTime"
            label="시작시간"
            required
            :error="errors.startTime"
          />
          <AppTimeField v-model="form.endTime" label="종료시간" required :error="errors.endTime" />
        </div>

        <AppField
          v-model="form.breakMinutes"
          type="number"
          label="휴게시간(분)"
          placeholder="0"
          hint="비워두면 휴게시간 없음으로 등록됩니다."
          :error="errors.breakMinutes"
        />

        <div class="field">
          <span class="field-label">휴게시간 급여</span>
          <div class="toggle" role="group" aria-label="휴게시간 급여 여부">
            <button
              type="button"
              class="toggle-btn"
              :class="{ active: !form.breakPaid }"
              @click="form.breakPaid = false"
            >
              무급
            </button>
            <button
              type="button"
              class="toggle-btn"
              :class="{ active: form.breakPaid }"
              @click="form.breakPaid = true"
            >
              유급
            </button>
          </div>
        </div>

        <AppField
          v-model="form.dailyWage"
          type="number"
          label="일급"
          placeholder="원 단위로 입력"
          required
          :hint="form.dailyWage ? formatKRW(form.dailyWage) : ''"
          :error="errors.dailyWage"
        />

        <BaseButton type="submit" variant="owner" size="lg" block :disabled="submitting">
          등록하기
        </BaseButton>
      </form>
    </main>
  </div>
</template>

<style scoped>
.screen-body {
  padding: var(--space-lg);
}
.form {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}

/* 휴게시간·일급은 화살표(스피너) 없이 숫자만 직접 입력한다 */
.form :deep(input[type='number']) {
  appearance: textfield;
  -moz-appearance: textfield;
}
.form :deep(input[type='number'])::-webkit-outer-spin-button,
.form :deep(input[type='number'])::-webkit-inner-spin-button {
  -webkit-appearance: none;
  appearance: none;
  margin: 0;
}

.field-row {
  display: flex;
  gap: var(--space-sm);
}
.field-row > * {
  flex: 1;
  min-width: 0;
}

/* 휴게시간 유급/무급 토글 — AppField 와 같은 라벨 스타일을 맞춘다 */
.field {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}
.field-label {
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-sub);
}
.toggle {
  display: flex;
  gap: var(--space-sm);
}
.toggle-btn {
  flex: 1;
  padding: var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  font-weight: var(--weight-medium);
  color: var(--color-text-sub);
}
.toggle-btn.active {
  border-color: var(--color-owner);
  color: var(--color-owner);
}
</style>
