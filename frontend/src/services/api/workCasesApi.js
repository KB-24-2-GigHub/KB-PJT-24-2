import http, { idempotentPost } from '@/services/http'

export async function getWorkCaseSummary(workplaceId) {
  const { data } = await http.get(`/workplaces/${workplaceId}/work-cases/summary`)
  return data
}

export async function listWorkCases(workplaceId, params = {}) {
  const { data } = await http.get(`/workplaces/${workplaceId}/work-cases`, { params })
  return data
}

export async function createWorkCase(workplaceId, payload) {
  const { data } = await http.post(`/workplaces/${workplaceId}/work-cases`, payload)
  return data
}

export async function getWorkCase(workCaseId) {
  const { data } = await http.get(`/work-cases/${workCaseId}`)
  return data
}

export async function updateWorkCase(workCaseId, payload) {
  await http.patch(`/work-cases/${workCaseId}`, payload)
}

export async function deleteWorkCase(workCaseId) {
  await http.delete(`/work-cases/${workCaseId}`)
}

export async function createInvite(workCaseId) {
  const { data } = await http.post(`/work-cases/${workCaseId}/invitations`)
  return data
}

export async function reissueInvite(workCaseId) {
  const { data } = await http.post(`/work-cases/${workCaseId}/invitations/reissue`)
  return data
}

export async function approveSettlement(workCaseId, { idempotencyKey = null } = {}) {
  const { data } = await idempotentPost(`/work-cases/${workCaseId}/settlement/approve`, undefined, {
    idempotencyKey
  })
  return data
}

export async function getOwnerContact(workCaseId) {
  const { data } = await http.get(`/work-cases/${workCaseId}/workplace-contact`)
  return data
}

export async function listReports(workCaseId) {
  const { data } = await http.get(`/work-cases/${workCaseId}/disputes`)
  return data
}

export async function createReport(workCaseId, { content }) {
  const { data } = await http.post(`/work-cases/${workCaseId}/disputes`, { content })
  return data
}
