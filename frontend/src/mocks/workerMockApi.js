/**
 * WORKER 홈·근무 이력 Mock — 실제 계약(WorkerHomeResponse·WorkerWorkCaseListItemResponse)과
 * 같은 모양을 쓴다. status는 work_case 8종 enum, 지각은 attendance.isLate의 파생값이다.
 */
const workCases = [
  {
    workCaseId: 101,
    title: '주말 홀 서빙',
    workplaceName: '카페 봄',
    startsAt: '2026-07-22T01:00:00Z', // KST 10:00
    endsAt: '2026-07-22T09:00:00Z', // KST 18:00
    breakMinutes: 60,
    breakPaid: false,
    dailyWage: 90000,
    status: 'IN_PROGRESS',
    attendance: {
      checkedInAt: '2026-07-22T01:15:00Z',
      checkedOutAt: null,
      isLate: true,
      lateMinutes: 15
    },
    escrowStatus: 'HELD',
    settlementStatus: 'WAITING',
    settlementDueAt: null
  },
  {
    workCaseId: 100,
    title: '평일 오전 준비',
    workplaceName: '카페 봄',
    startsAt: '2026-07-20T00:00:00Z', // KST 09:00
    endsAt: '2026-07-20T04:00:00Z', // KST 13:00
    breakMinutes: 0,
    breakPaid: false,
    dailyWage: 60000,
    status: 'COMPLETED',
    attendance: {
      checkedInAt: '2026-07-20T00:00:00Z',
      checkedOutAt: '2026-07-20T04:00:00Z',
      isLate: false,
      lateMinutes: null
    },
    escrowStatus: 'RELEASED',
    settlementStatus: 'COMPLETED',
    settlementDueAt: null
  }
]

export async function getWorkerHome() {
  return {
    todayWorkCase: {
      ...workCases[0],
      expectedNetAmount: 90000
    }
  }
}

export async function listWorkerWorkCases() {
  return {
    content: workCases.map((workCase) => ({ ...workCase })),
    page: { number: 0, size: 20, totalElements: workCases.length, totalPages: 1 }
  }
}

// 승인 계약이 고정한 다섯 필드와 공통 Page Envelope 를 그대로 쓴다. workCaseId 는 응답에
// 포함하지 않는다 — 공유 요청은 workplaceId 만 보내고 서버가 Work Case 를 파생한다.
export async function listWorkerWorkplaces() {
  const workplaces = [
    {
      workplaceId: 1,
      workplaceName: '강남점',
      ownerName: '김사장',
      startsAt: '2026-08-20T01:00:00Z',
      endsAt: '2026-08-20T09:00:00Z'
    }
  ]
  return {
    content: workplaces,
    page: { number: 0, size: 20, totalElements: workplaces.length, totalPages: 1 }
  }
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
