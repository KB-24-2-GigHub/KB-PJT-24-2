import { defineStore } from 'pinia'
import { ref } from 'vue'

import { getWorkerHome } from '@/services/worker'
import { formatSeoulDateKey, formatSeoulTime } from '@/utils/format'

/**
 * 알바생 홈 화면 상태.
 * 서버 데이터의 최종 원본은 백엔드이며, 여기서는 화면 표시용 상태만 보관한다.
 *
 * 안심지갑 잔액은 이 Store가 아니라 OWNER와 공유하는 @/stores/wallet이 단일 원천이다
 * (#151). GET /worker/home 응답의 wallet 필드는 화면에서 읽지 않는다.
 *
 * `GET /worker/home`은 최상위에 `todayWorkCase` 하나만 준다(별도 `earning` 필드 없음,
 * WorkerHomeResponse.java 참고). `earning`은 표시 전용 파생값이라 여기서 todayWorkCase로부터
 * 만든다 — 지갑 잔액·정산 금액처럼 서버 원본을 그대로 옮기는 값이 아니다(DASH-001).
 */
export const useWorkerHomeStore = defineStore('workerHome', () => {
  const todayWorkCase = ref(null) // 오늘의 알바 일정(표시용 workDate/startTime/endTime 포함)
  const earning = ref(null) // 근무 경과 예상금액(참고용, todayWorkCase로부터 파생)
  const loading = ref(false)
  const error = ref(null)

  /**
   * 표시 계산(calcElapsedPay/useEarningTick)은 워크지 기준 벽시계 문자열을 받는다.
   * startsAt/endsAt(UTC Instant)에서 Asia/Seoul 기준으로 뽑아 붙인다.
   */
  function mapTodayWorkCase(raw) {
    if (!raw) return null
    return {
      ...raw,
      workDate: formatSeoulDateKey(raw.startsAt),
      startTime: formatSeoulTime(raw.startsAt),
      endTime: formatSeoulTime(raw.endsAt)
    }
  }

  /** 지각은 별도 상태가 아니라 attendance.isLate의 파생 표시다(workCaseStatus.js 참고). */
  function mapEarning(workCase) {
    if (!workCase) return null
    return {
      agreedWage: workCase.dailyWage,
      expectedNetAmount: workCase.expectedNetAmount,
      isLate: !!workCase.attendance?.isLate,
      lateMinutes: workCase.attendance?.lateMinutes ?? 0
    }
  }

  async function loadHome() {
    loading.value = true
    error.value = null
    try {
      const data = await getWorkerHome()
      todayWorkCase.value = mapTodayWorkCase(data.todayWorkCase)
      earning.value = mapEarning(todayWorkCase.value)
    } catch (e) {
      error.value = e
    } finally {
      loading.value = false
    }
  }

  return { todayWorkCase, earning, loading, error, loadHome }
})
