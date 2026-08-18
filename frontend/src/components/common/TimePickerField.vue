<script setup>
/**
 * 시각 입력 필드 — 트리거 버튼 + 바텀시트(오전·오후 / 시 / 분) 선택.
 *
 * input[type=time] 을 대신한다. 네이티브 위젯은 브라우저·OS 마다 다르게 그려져 같은 폼
 * 안에서 높이와 타이포가 어긋나고, step 으로 분 단위를 제약해도 모바일 OS 휠은 1분
 * 단위를 그대로 보여준다. 그래서 눈금을 직접 그린다.
 *
 * 값 계약: 화면은 12시간제("오후 5:00"), 모델은 24시간제 "HH:mm".
 *   <TimePickerField v-model="form.startTime" label="시작시간" accent="owner" />
 *
 * maxFrom 을 주면 기준 시각으로부터 허용 길이를 넘는 눈금을 아예 고를 수 없게 만든다.
 * 에러 메시지로 알리는 대신 만들 수 없게 하는 쪽이다. 검증 자체는 폼의 validator 가
 * 그대로 들고 있다 — 서버 응답과 프로그램 경로는 이 위젯을 거치지 않는다.
 */
import { computed, reactive, ref, watch } from 'vue'

import BaseBottomSheet from '@/components/common/BaseBottomSheet.vue'
import { parseWallClockMinutes } from '@/utils/format'

const props = defineProps({
  modelValue: { type: String, default: '' },
  label: { type: String, default: '' },
  placeholder: { type: String, default: '시간 선택' },
  required: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  error: { type: String, default: '' },
  hint: { type: String, default: '' },
  accent: { type: String, default: 'primary' },
  /** 분 눈금 간격. 60 의 약수여야 한다. */
  minuteStep: { type: Number, default: 10 },
  /** 값이 비어 있을 때 시트를 열면 여기에 커서를 둔다. 빈 화면으로 열지 않기 위한 것. */
  defaultTime: { type: String, default: '09:00' },
  /**
   * 길이 상한. `{ time: "HH:mm", minutes: number }`.
   * time 이 비었거나 읽히지 않으면 아무 눈금도 막지 않는다.
   */
  maxFrom: { type: Object, default: null }
})

const emit = defineEmits(['update:modelValue'])

const MINUTES_PER_DAY = 24 * 60
/** 12시간제 시계 배열 — 12 가 맨 앞에 오는 문자판 순서 그대로다. */
const HOUR_LABELS = [12, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11]

const open = ref(false)
const draft = reactive({ meridiem: 'AM', hour: 9, minute: 0 })

const minuteOptions = computed(() => {
  const step = props.minuteStep > 0 ? props.minuteStep : 10
  return Array.from({ length: Math.ceil(60 / step) }, (_, i) => i * step)
})

/** 12시간제 (오전|오후, 1~12) → 자정 기준 시(0~23). */
function to24(meridiem, hour12) {
  const base = hour12 % 12
  return meridiem === 'PM' ? base + 12 : base
}

function toDisplay(totalMinutes) {
  const hour24 = Math.floor(totalMinutes / 60)
  const minute = totalMinutes % 60
  const meridiem = hour24 < 12 ? '오전' : '오후'
  const hour12 = hour24 % 12 === 0 ? 12 : hour24 % 12
  return `${meridiem} ${hour12}:${String(minute).padStart(2, '0')}`
}

const selectedMinutes = computed(() => parseWallClockMinutes(props.modelValue))

const displayText = computed(() =>
  selectedMinutes.value === null ? props.placeholder : toDisplay(selectedMinutes.value)
)

/**
 * 기준 시각에서 앞으로 흐른 거리로 판단한다. 자정을 넘기는 근무가 있으므로 단순 대소
 * 비교로는 잴 수 없다. 거리 0(기준과 같은 시각)은 0분이 아니라 24시간이라 상한 밖이다.
 */
function isAllowed(hour24, minute) {
  const limit = props.maxFrom
  if (!limit) return true

  const from = parseWallClockMinutes(limit.time)
  if (from === null || !Number.isFinite(limit.minutes)) return true

  const distance = (hour24 * 60 + minute - from + MINUTES_PER_DAY) % MINUTES_PER_DAY
  return distance > 0 && distance <= limit.minutes
}

function isHourDisabled(meridiem, hour12) {
  const hour24 = to24(meridiem, hour12)
  return !minuteOptions.value.some((minute) => isAllowed(hour24, minute))
}

const isMeridiemDisabled = (meridiem) =>
  HOUR_LABELS.every((hour12) => isHourDisabled(meridiem, hour12))

const draftMinutes = computed(() => to24(draft.meridiem, draft.hour) * 60 + draft.minute)
const draftDisplay = computed(() => toDisplay(draftMinutes.value))
const confirmDisabled = computed(() => !isAllowed(to24(draft.meridiem, draft.hour), draft.minute))

/** 고를 수 없는 눈금 위에 커서가 남지 않게 시·분을 차례로 끌어당긴다. */
function settleDraft() {
  if (isHourDisabled(draft.meridiem, draft.hour)) {
    const hour = HOUR_LABELS.find((candidate) => !isHourDisabled(draft.meridiem, candidate))
    if (hour !== undefined) draft.hour = hour
  }

  const hour24 = to24(draft.meridiem, draft.hour)
  if (!isAllowed(hour24, draft.minute)) {
    const minute = minuteOptions.value.find((candidate) => isAllowed(hour24, candidate))
    if (minute !== undefined) draft.minute = minute
  }
}

/**
 * 시트를 열 때만 모델값을 초안으로 옮긴다. 초안은 확인을 누르기 전까지 모델과 분리돼
 * 있어야 취소가 취소로 동작한다.
 *
 * 눈금에 걸리지 않는 값(서버에 09:25 로 저장된 근무)은 아래쪽 눈금에 커서만 두고
 * 모델은 손대지 않는다. 여기서 반올림해 올리면 사용자가 열어 보기만 한 근무의 시각이
 * 바뀐다.
 */
function syncDraftFromModel() {
  const source = selectedMinutes.value ?? parseWallClockMinutes(props.defaultTime) ?? 0
  const hour24 = Math.floor(source / 60)
  const step = props.minuteStep > 0 ? props.minuteStep : 10

  draft.meridiem = hour24 < 12 ? 'AM' : 'PM'
  draft.hour = hour24 % 12 === 0 ? 12 : hour24 % 12
  draft.minute = Math.floor((source % 60) / step) * step

  if (isMeridiemDisabled(draft.meridiem)) draft.meridiem = draft.meridiem === 'AM' ? 'PM' : 'AM'
  settleDraft()
}

watch(open, (isOpen) => {
  if (isOpen) syncDraftFromModel()
})

function selectMeridiem(meridiem) {
  draft.meridiem = meridiem
  settleDraft()
}

function selectHour(hour12) {
  draft.hour = hour12
  settleDraft()
}

function selectMinute(minute) {
  draft.minute = minute
}

function confirm() {
  if (confirmDisabled.value) return
  const hour24 = to24(draft.meridiem, draft.hour)
  emit(
    'update:modelValue',
    `${String(hour24).padStart(2, '0')}:${String(draft.minute).padStart(2, '0')}`
  )
  open.value = false
}
</script>

<template>
  <div class="field" :class="[`accent-${accent}`, { 'has-error': error }]">
    <span v-if="label" class="label">
      {{ label }}
      <span v-if="required" class="req" aria-hidden="true">*</span>
    </span>

    <button
      type="button"
      class="time-trigger"
      :class="{ empty: selectedMinutes === null }"
      :disabled="disabled"
      :aria-label="`${label} ${selectedMinutes === null ? '선택 안 함' : displayText}`"
      @click="open = true"
    >
      {{ displayText }}
    </button>

    <p v-if="error" class="msg error" role="alert">{{ error }}</p>
    <p v-else-if="hint" class="msg hint">{{ hint }}</p>

    <BaseBottomSheet :open="open" :title="label || '시간 선택'" @close="open = false">
      <div class="picker" :class="`accent-${accent}`">
        <div class="meridiem" role="radiogroup" aria-label="오전 오후">
          <button
            v-for="option in [
              { key: 'AM', text: '오전' },
              { key: 'PM', text: '오후' }
            ]"
            :key="option.key"
            type="button"
            class="meridiem-btn"
            role="radio"
            :aria-checked="draft.meridiem === option.key"
            :disabled="isMeridiemDisabled(option.key)"
            @click="selectMeridiem(option.key)"
          >
            {{ option.text }}
          </button>
        </div>

        <div class="hours" role="radiogroup" aria-label="시">
          <button
            v-for="hour12 in HOUR_LABELS"
            :key="hour12"
            type="button"
            class="hour-btn"
            role="radio"
            :aria-checked="draft.hour === hour12"
            :disabled="isHourDisabled(draft.meridiem, hour12)"
            @click="selectHour(hour12)"
          >
            {{ hour12 }}
          </button>
        </div>

        <div class="minutes" role="radiogroup" aria-label="분">
          <button
            v-for="minute in minuteOptions"
            :key="minute"
            type="button"
            class="minute-btn"
            role="radio"
            :aria-checked="draft.minute === minute"
            :disabled="!isAllowed(to24(draft.meridiem, draft.hour), minute)"
            @click="selectMinute(minute)"
          >
            {{ String(minute).padStart(2, '0') }}
          </button>
        </div>

        <p class="picker-preview">{{ draftDisplay }}</p>
      </div>

      <template #footer>
        <!-- 시트는 Teleport 로 body 에 붙으므로 필드에 준 --accent 가 여기까지 닿지 않는다 -->
        <button
          type="button"
          class="picker-confirm"
          :class="`accent-${accent}`"
          :disabled="confirmDisabled"
          @click="confirm"
        >
          확인
        </button>
      </template>
    </BaseBottomSheet>
  </div>
</template>

<style scoped>
.field {
  --accent: var(--color-primary);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}
.accent-owner {
  --accent: var(--color-owner);
}
.accent-worker {
  --accent: var(--color-worker);
}

.label {
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-sub);
}
.req {
  color: var(--color-danger);
}

/* AppField 의 .input 과 같은 치수를 쓴다. 한 폼에 섞여도 줄이 어긋나지 않아야 한다. */
.time-trigger {
  width: 100%;
  min-height: 48px;
  padding: var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  text-align: left;
  font-size: var(--text-md);
  font-weight: var(--weight-medium);
  color: var(--color-text);
  /* 11:00 → 12:00 에서 폭이 흔들리지 않게 고정폭 숫자를 쓴다 */
  font-variant-numeric: tabular-nums;
}
.time-trigger.empty {
  font-weight: var(--weight-regular);
  color: var(--color-text-sub);
}
.time-trigger:focus-visible {
  outline: none;
  border-color: var(--accent);
}
.time-trigger:disabled {
  color: var(--color-text-sub);
  background: var(--color-bg);
  cursor: not-allowed;
}
.field.has-error .time-trigger {
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

/* ---- 시트 내부 ---- */
.picker {
  --accent: var(--color-primary);
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}
.picker.accent-owner {
  --accent: var(--color-owner);
}
.picker.accent-worker {
  --accent: var(--color-worker);
}

.meridiem {
  display: flex;
  gap: var(--space-sm);
}
.hours {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--space-sm);
}
.minutes {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: var(--space-sm);
  padding-top: var(--space-lg);
  border-top: 1px solid var(--color-border);
}

.meridiem-btn,
.hour-btn,
.minute-btn {
  /* 손가락 탭 최소 치수 */
  min-height: 44px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  font-size: var(--text-md);
  font-weight: var(--weight-medium);
  color: var(--color-text);
  font-variant-numeric: tabular-nums;
  transition:
    background-color 0.12s ease,
    color 0.12s ease,
    border-color 0.12s ease;
}
.meridiem-btn {
  flex: 1;
}

/* 선택은 테두리 색이 아니라 채움으로 알린다 — 테두리만 바꾸면 한눈에 안 들어온다 */
.meridiem-btn[aria-checked='true'],
.hour-btn[aria-checked='true'],
.minute-btn[aria-checked='true'] {
  border-color: var(--accent);
  background: var(--accent);
  color: var(--color-on-primary);
}
.meridiem-btn:disabled,
.hour-btn:disabled,
.minute-btn:disabled {
  color: var(--color-border);
  background: var(--color-bg);
  cursor: not-allowed;
}

.picker-preview {
  text-align: center;
  font-size: var(--text-xl);
  font-weight: var(--weight-bold);
  color: var(--color-text);
  font-variant-numeric: tabular-nums;
}

.picker-confirm {
  --accent: var(--color-primary);
  width: 100%;
  min-height: 48px;
  border-radius: var(--radius-sm);
  background: var(--accent);
  font-size: var(--text-lg);
  font-weight: var(--weight-bold);
  color: var(--color-on-primary);
}
.picker-confirm.accent-owner {
  --accent: var(--color-owner);
}
.picker-confirm.accent-worker {
  --accent: var(--color-worker);
}
.picker-confirm:disabled {
  background: var(--color-border);
  cursor: not-allowed;
}

@media (prefers-reduced-motion: reduce) {
  .meridiem-btn,
  .hour-btn,
  .minute-btn {
    transition: none;
  }
}
</style>
