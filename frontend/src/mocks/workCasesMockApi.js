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
    completed: count('COMPLETED'),
    noShow: count('NO_SHOW')
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
  return { settlementId: 1, status: 'COMPLETED', completedAt: new Date().toISOString() }
}

export async function getOwnerContact() {
  return { ownerName: '김사장', phone: '01012345678' }
}

export async function listReports() {
  return { content: [] }
}

export async function createReport() {
  return { reportId: nextReportId++ }
}
