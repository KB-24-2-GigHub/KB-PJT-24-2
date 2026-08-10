import http from '@/services/http'
import { onlyDigits } from '@/utils/format'
import { normalizePhone } from '@/utils/validators'

const MIN_PAGE_SIZE = 1
const MAX_PAGE_SIZE = 100

function withNormalizedPhone(payload) {
  if (!payload || payload.phone === undefined) return payload
  return { ...payload, phone: normalizePhone(payload.phone) }
}

export async function listWorkplaces({ page = 0, size = MAX_PAGE_SIZE } = {}) {
  const boundedSize = Math.min(Math.max(size, MIN_PAGE_SIZE), MAX_PAGE_SIZE)
  const { data } = await http.get('/workplaces', { params: { page, size: boundedSize } })
  return data
}

export async function createWorkplace({
  businessRegistrationNumber,
  name,
  representativeName,
  roadAddress,
  detailAddress,
  phone
}) {
  const body = {
    businessRegistrationNumber: onlyDigits(businessRegistrationNumber),
    name,
    representativeName,
    roadAddress,
    phone: normalizePhone(phone)
  }
  const trimmedDetail = detailAddress?.trim()
  if (trimmedDetail) body.detailAddress = trimmedDetail
  const { data } = await http.post('/workplaces', body)
  return data
}

export async function updateWorkplace(workplaceId, payload) {
  const { data } = await http.patch(`/workplaces/${workplaceId}`, withNormalizedPhone(payload))
  return data
}

export async function deleteWorkplace(workplaceId) {
  await http.delete(`/workplaces/${workplaceId}`)
}

export async function getWorkplaceQr(workplaceId) {
  const { data } = await http.get(`/workplaces/${workplaceId}/qr`)
  return data
}

export async function reissueWorkplaceQr(workplaceId) {
  const { data } = await http.post(`/workplaces/${workplaceId}/qr/reissue`)
  return data
}
