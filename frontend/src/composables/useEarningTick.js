import { onMounted, onUnmounted, ref, unref, watch } from 'vue'

import { calcElapsedPay, calcLateProgress } from '@/utils/earning'

const TICK_MS = 60_000

/**
 * 근무 경과 예상금액(참고용)을 1분마다 다시 계산한다.
 * 기준값(일급·근무 시각)은 서버가 준 값을 그대로 쓰고, 시간 축 파생값만 여기서 만든다.
 *
 * @param {import('vue').Ref<object|null>|object|null} earning  { agreedWage, checkedInAt }
 * @param {import('vue').Ref<object|null>|object|null} workCase { workDate, startTime, endTime }
 */
export function useEarningTick(earning, workCase) {
  const elapsedPay = ref(0)
  const progressRatio = ref(0)
  const lateRatio = ref(0)
  let timer = null

  function stop() {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  function recalc() {
    const e = unref(earning)
    const w = unref(workCase)

    if (!e || !w) {
      elapsedPay.value = 0
      progressRatio.value = 0
      lateRatio.value = 0
      stop()
      return
    }

    const result = calcElapsedPay({
      agreedWage: e.agreedWage,
      workDate: w.workDate,
      startTime: w.startTime,
      endTime: w.endTime,
      checkedInAt: e.checkedInAt
    })
    elapsedPay.value = result.elapsedPay
    progressRatio.value = result.progressRatio

    // 체크인 전엔 지각 경과가 실시간으로 늘어나고, 체크인 후엔 체크인 시점의 지각 폭
    // (result.lateRatio)으로 고정된다 — 그 옆에 근무 경과(progressRatio)가 새로 쌓인다.
    lateRatio.value = e.checkedInAt
      ? result.lateRatio
      : calcLateProgress({ workDate: w.workDate, startTime: w.startTime, endTime: w.endTime })

    // 근무가 끝나거나(진행률 100%) 체크인 전 지각 표시가 다 찼으면(예정 종료 시각을
    // 넘겨 체크인 없이 방치) 값이 더 이상 변하지 않는다 — 타이머를 돌릴 이유가 없다.
    if (result.progressRatio >= 1 || lateRatio.value >= 1) stop()
  }

  function start() {
    recalc()
    if (timer === null && unref(earning) && progressRatio.value < 1 && lateRatio.value < 1) {
      timer = setInterval(recalc, TICK_MS)
    }
  }

  // 모바일 백그라운드에서는 타이머가 throttle 된다. 복귀 시 즉시 현재 시각으로 맞춘다.
  function onVisibilityChange() {
    if (document.visibilityState !== 'hidden') recalc()
  }

  onMounted(() => {
    start()
    document.addEventListener('visibilitychange', onVisibilityChange)
  })

  onUnmounted(() => {
    stop()
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })

  // 홈 데이터가 비동기로 뒤늦게 도착하는 경우를 받아준다.
  watch(() => unref(earning), start)

  return { elapsedPay, progressRatio, lateRatio }
}
