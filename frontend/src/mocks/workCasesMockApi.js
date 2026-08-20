const workCases = [
  {
    workCaseId: 101,
    workplaceId: 1,
    workplaceName: '강남점',
    title: '주말 홀 서빙',
    workDate: '2026-07-22',
    startsAt: '2026-07-22T01:00:00Z',
    endsAt: '2026-07-22T09:00:00Z',
    dailyWage: 90000,
    status: 'ACCEPTED',
    worker: { workerId: 1001, name: '이알바' }
  },
  {
    workCaseId: 102,
    workplaceId: 1,
    workplaceName: '강남점',
    title: '오전 준비',
    workDate: '2026-07-23',
    startsAt: '2026-07-23T00:00:00Z',
    endsAt: '2026-07-23T04:00:00Z',
    dailyWage: 60000,
    status: 'DRAFT',
    worker: null
  },
  {
    workCaseId: 104,
    workplaceId: 1,
    workplaceName: '강남점',
    title: '저녁 마감',
    workDate: '2026-07-24',
    startsAt: '2026-07-24T09:00:00Z',
    endsAt: '2026-07-24T13:00:00Z',
    dailyWage: 60000,
    status: 'DRAFT',
    worker: null
  }
]
let nextWorkCaseId = 102
let nextReportId = 1
const reportsByWorkCase = new Map()

function filtered(workplaceId, params = {}) {
  const keyword = String(params.keyword ?? '')
    .trim()
    .toLowerCase()
  return workCases.filter(
    (workCase) =>
      workCase.workplaceId === Number(workplaceId) &&
      (!params.status || workCase.status === params.status) &&
      (!params.from || workCase.workDate >= params.from) &&
      (!params.to || workCase.workDate <= params.to) &&
      (!keyword ||
        workCase.title.toLowerCase().includes(keyword) ||
        String(workCase.worker?.name ?? '')
          .toLowerCase()
          .includes(keyword))
  )
}

export async function getWorkCaseSummary(workplaceId) {
  const matches = filtered(workplaceId)
  const count = (status) => matches.filter((workCase) => workCase.status === status).length
  return {
    draft: count('DRAFT'),
    accepted: count('ACCEPTED'),
    ready: count('READY'),
    inProgress: count('IN_PROGRESS'),
    checkOutMissing: count('CHECK_OUT_MISSING'),
    completed: count('COMPLETED'),
    noShow: count('NO_SHOW'),
    canceled: count('CANCELED')
  }
}

export async function listWorkCases(workplaceId, params = {}) {
  const content = filtered(workplaceId, params).map((workCase) => {
    const item = {
      ...workCase,
      worker: workCase.worker ? { ...workCase.worker } : null
    }
    Reflect.deleteProperty(item, 'workplaceId')
    return item
  })
  return {
    content,
    page: { number: 0, size: content.length || 1, totalElements: content.length, totalPages: 1 }
  }
}

export async function createWorkCase(workplaceId, payload) {
  const workCaseId = nextWorkCaseId++
  workCases.push({ ...payload, workCaseId, workplaceId, status: 'DRAFT', worker: null })
  return { workCaseId }
}

export async function getWorkCase(workCaseId) {
  const workCase = workCases.find((item) => item.workCaseId === Number(workCaseId))
  return {
    ...(workCase ?? workCases[0]),
    workCaseId: Number(workCaseId),
    latestInvitation: null,
    contract: null,
    attendance: { checkedInAt: null, checkedOutAt: null },
    escrow: null,
    settlement: null
  }
}

export async function updateWorkCase(workCaseId, payload) {
  const target = workCases.find((workCase) => workCase.workCaseId === Number(workCaseId))
  if (target) Object.assign(target, payload)
}

export async function deleteWorkCase(workCaseId) {
  const index = workCases.findIndex((workCase) => workCase.workCaseId === Number(workCaseId))
  if (index >= 0) workCases.splice(index, 1)
}

export async function createInvite(workCaseId) {
  return {
    inviteUrl: `${location.origin}/invitations/mock-token-${workCaseId}`,
    expiresAt: '2026-07-23T23:59:59Z'
  }
}

export async function reissueInvite(workCaseId) {
  return {
    inviteUrl: `${location.origin}/invitations/mock-token-${workCaseId}-reissued`,
    expiresAt: '2026-07-23T23:59:59Z'
  }
}

export async function approveSettlement() {
  return {
    settlementId: 1,
    status: 'COMPLETED',
    originalEscrowAmount: 100000,
    workerPaidAmount: 85710,
    ownerRefundAmount: 14290,
    deductionAmount: 14290,
    deductionBaseMinutes: 210,
    lateMinutes: 30,
    earlyLeaveMinutes: 0,
    calculationReason: 'CHECKED_OUT',
    calculationVersion: 'ATTENDANCE_V1',
    calculatedAt: new Date().toISOString(),
    completedAt: new Date().toISOString()
  }
}

export async function approveNoShowRefund() {
  return {
    settlementId: 1,
    status: 'REFUNDED',
    originalEscrowAmount: 90000,
    workerPaidAmount: 0,
    ownerRefundAmount: 90000,
    deductionAmount: 90000,
    deductionBaseMinutes: 210,
    lateMinutes: 0,
    earlyLeaveMinutes: 0,
    calculationReason: 'NO_SHOW',
    calculationVersion: 'ATTENDANCE_V1',
    calculatedAt: new Date().toISOString(),
    completedAt: new Date().toISOString()
  }
}

export async function approveCheckOutMissingRefund() {
  return {
    settlementId: 1,
    status: 'REFUNDED',
    originalEscrowAmount: 90000,
    workerPaidAmount: 0,
    ownerRefundAmount: 90000,
    deductionAmount: 90000,
    deductionBaseMinutes: 210,
    lateMinutes: 0,
    earlyLeaveMinutes: 0,
    calculationReason: 'CHECK_OUT_MISSING',
    calculationVersion: 'ATTENDANCE_V1',
    calculatedAt: new Date().toISOString(),
    completedAt: new Date().toISOString()
  }
}

export async function getOwnerContact() {
  return { ownerName: '김사장', phone: '01012345678' }
}

export async function listReports(workCaseId) {
  const content = reportsByWorkCase.get(Number(workCaseId)) ?? []
  return {
    content: content.map((report) => ({
      ...report,
      demoReview: report.demoReview ? { ...report.demoReview } : null
    })),
    page: {
      number: 0,
      size: content.length || 20,
      totalElements: content.length,
      totalPages: content.length ? 1 : 0
    }
  }
}

export async function createReport(workCaseId, payload) {
  const normalizedWorkCaseId = Number(workCaseId)
  const reports = reportsByWorkCase.get(normalizedWorkCaseId) ?? []
  if (reports.some((report) => ['OPEN', 'UNDER_REVIEW'].includes(report.status))) {
    const duplicate = new Error('이미 처리 중인 분쟁이 있습니다.')
    duplicate.code = 'DISPUTE_ALREADY_OPEN'
    throw duplicate
  }

  const reportId = nextReportId++
  reports.unshift({
    reportId,
    title: String(payload?.title ?? '').trim(),
    content: String(payload?.content ?? '').trim(),
    status: 'OPEN',
    resolution: null,
    requesterRole: 'WORKER',
    createdAt: new Date().toISOString(),
    resolvedAt: null,
    demoReview: {
      source: 'SIMULATED_LLM',
      status: 'PENDING',
      decision: null,
      reasonCodes: [],
      summary: null,
      confidence: null,
      reviewedAt: null
    }
  })
  reportsByWorkCase.set(normalizedWorkCaseId, reports)
  return { reportId }
}
