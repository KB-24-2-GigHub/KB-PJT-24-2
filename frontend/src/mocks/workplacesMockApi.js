import { onlyDigits } from '@/utils/format'
import { normalizePhone } from '@/utils/validators'

const workplaces = [
  {
    workplaceId: 1,
    businessRegistrationNumber: '1234567890',
    name: '강남점',
    representativeName: '김사장',
    roadAddress: '서울 강남구 테헤란로 1',
    detailAddress: '2층',
    phone: '0212345678',
    radiusMeters: 100,
    status: 'ACTIVE'
  },
  {
    workplaceId: 2,
    businessRegistrationNumber: '9876543210',
    name: '홍대점',
    representativeName: '김사장',
    roadAddress: '서울 마포구 양화로 100',
    detailAddress: '',
    phone: '023334444',
    radiusMeters: 100,
    status: 'ACTIVE'
  }
]
let nextWorkplaceId = 3

export async function listWorkplaces({ page = 0, size = 100 } = {}) {
  const boundedSize = Math.min(Math.max(size, 1), 100)
  return {
    content: workplaces.map((workplace) => ({ ...workplace })),
    page: { number: page, size: boundedSize, totalElements: workplaces.length, totalPages: 1 }
  }
}

export async function createWorkplace(payload) {
  const workplaceId = nextWorkplaceId++
  workplaces.push({
    ...payload,
    workplaceId,
    businessRegistrationNumber: onlyDigits(payload.businessRegistrationNumber),
    phone: normalizePhone(payload.phone),
    detailAddress: payload.detailAddress?.trim() || '',
    radiusMeters: 100,
    status: 'ACTIVE'
  })
  return { workplaceId }
}

export async function updateWorkplace(workplaceId, payload) {
  const normalized =
    payload.phone === undefined ? payload : { ...payload, phone: normalizePhone(payload.phone) }
  const target = workplaces.find((workplace) => workplace.workplaceId === workplaceId)
  if (target) Object.assign(target, normalized)
  return { workplaceId, ...normalized }
}

export async function deleteWorkplace(workplaceId) {
  const index = workplaces.findIndex((workplace) => workplace.workplaceId === workplaceId)
  if (index >= 0) workplaces.splice(index, 1)
}
