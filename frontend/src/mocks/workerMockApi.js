const workCases = [
  {
    workCaseId: 101,
    workplaceName: '강남점',
    workDate: '2026-07-22',
    time: '10:00 ~ 18:00',
    dailyWage: 90000,
    status: 'IN_PROGRESS',
    settleStatus: 'HOLD'
  }
]

export async function getWorkerHome() {
  return {
    wallet: { availableBalance: 320000 },
    todayWorkCase: {
      status: 'LATE',
      title: '주말 홀 서빙',
      workplaceName: '카페 봄',
      workDate: '2026-07-22',
      startTime: '10:00',
      endTime: '18:00'
    },
    earning: {
      agreedWage: 90000,
      totalMinutes: 480,
      unpaidBreakMinutes: 60,
      elapsedPayDisplay: 34526,
      progressRatio: 0.42,
      expectedNetAmount: 90000,
      isLate: true,
      lateMinutes: 15
    }
  }
}

export async function listWorkerWorkCases() {
  return { content: workCases.map((workCase) => ({ ...workCase })), totalPages: 1 }
}

export async function listWorkerWorkplaces() {
  return [{ workplaceId: 1, workplaceName: '강남점', ownerName: '김사장' }]
}

export async function scan() {
  return {
    result: 'RECORDED',
    workCaseId: 101,
    scanType: 'CHECK_IN',
    recordedAt: new Date().toISOString(),
    isLate: false,
    lateMinutes: 0,
    earlyCheckoutConfirmedAt: null,
    settlementDueAt: null
  }
}
