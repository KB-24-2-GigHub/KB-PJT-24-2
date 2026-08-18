// 임시 프리뷰 — 시각 피커 시안 확인용. 확인 후 삭제한다.
import '@/assets/main.css'

import { computed, createApp, h, reactive } from 'vue'

import AppField from '@/components/common/AppField.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import TimePickerField from '@/components/common/TimePickerField.vue'
import { formatKRW, formatWorkPeriodSummary } from '@/utils/format'
import { WORK_DURATION_MAX_MINUTES } from '@/utils/validators'

const Preview = {
  setup() {
    const form = reactive({
      title: '주말 홀 서빙',
      workDate: '2026-08-22',
      startTime: '',
      endTime: '',
      breakMinutes: '60',
      breakPaid: false,
      dailyWage: '90000'
    })

    const endTimeLimit = computed(() => ({
      time: form.startTime,
      minutes: WORK_DURATION_MAX_MINUTES
    }))

    const periodSummary = computed(() =>
      formatWorkPeriodSummary({
        startTime: form.startTime,
        endTime: form.endTime,
        breakMinutes: Number(form.breakMinutes || 0),
        breakPaid: form.breakPaid
      })
    )

    return () =>
      h('div', { class: 'sub-page' }, [
        h('header', { class: 'preview-head' }, '근무 포지션 추가'),
        h('main', { class: 'screen-body' }, [
          h('form', { class: 'form', onSubmit: (e) => e.preventDefault() }, [
            h(AppField, {
              modelValue: form.title,
              'onUpdate:modelValue': (v) => (form.title = v),
              label: '제목',
              required: true
            }),
            h(AppField, {
              modelValue: form.workDate,
              'onUpdate:modelValue': (v) => (form.workDate = v),
              type: 'date',
              label: '근무 날짜',
              required: true
            }),
            h('div', { class: 'time-group' }, [
              h('div', { class: 'field-row' }, [
                h(TimePickerField, {
                  modelValue: form.startTime,
                  'onUpdate:modelValue': (v) => (form.startTime = v),
                  label: '시작시간',
                  accent: 'owner',
                  required: true
                }),
                h(TimePickerField, {
                  modelValue: form.endTime,
                  'onUpdate:modelValue': (v) => (form.endTime = v),
                  label: '종료시간',
                  accent: 'owner',
                  required: true,
                  defaultTime: form.startTime || '18:00',
                  maxFrom: endTimeLimit.value
                })
              ]),
              periodSummary.value ? h('p', { class: 'period-summary' }, periodSummary.value) : null
            ]),
            h(AppField, {
              modelValue: form.breakMinutes,
              'onUpdate:modelValue': (v) => (form.breakMinutes = v),
              type: 'number',
              label: '휴게시간(분)',
              hint: '비워두면 휴게시간 없음으로 등록됩니다.'
            }),
            h(AppField, {
              modelValue: form.dailyWage,
              'onUpdate:modelValue': (v) => (form.dailyWage = v),
              type: 'number',
              label: '일급',
              required: true,
              hint: form.dailyWage ? formatKRW(form.dailyWage) : ''
            }),
            h(BaseButton, { variant: 'owner', size: 'lg', block: true }, () => '등록하기')
          ])
        ])
      ])
  }
}

const style = document.createElement('style')
style.textContent = `
  .sub-page { max-width: 430px; margin: 0 auto; background: var(--color-surface); min-height: 100vh; }
  .preview-head { padding: var(--space-lg); border-bottom: 1px solid var(--color-border);
    font-size: var(--text-xl); font-weight: var(--weight-bold); }
  .screen-body { padding: var(--space-lg); }
  .form { display: flex; flex-direction: column; gap: var(--space-lg); }
  .field-row { display: flex; gap: var(--space-sm); }
  .field-row > * { flex: 1; min-width: 0; }
  .time-group { display: flex; flex-direction: column; gap: var(--space-sm); }
  .period-summary { padding: var(--space-sm) var(--space-md); border-radius: var(--radius-sm);
    background: var(--color-owner-weak); font-size: var(--text-sm);
    font-weight: var(--weight-medium); color: var(--color-owner);
    font-variant-numeric: tabular-nums; }
`
document.head.appendChild(style)

createApp(Preview).mount('#preview')
