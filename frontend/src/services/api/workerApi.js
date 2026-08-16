import http from '@/services/http'
import { idempotentPost } from '@/services/http'

export async function getWorkerHome() {
  const { data } = await http.get('/worker/home')
  return data
}

export async function listWorkerWorkCases(params = {}) {
  const { data } = await http.get('/worker/work-cases', { params })
  return data
}

export async function listWorkerWorkplaces(params = {}) {
  const { data } = await http.get('/worker/workplaces', { params })
  return data
}

export async function scan({
  qrToken,
  latitude,
  longitude,
  accuracyMeters,
  capturedAt,
  confirmEarlyCheckout = false,
  idempotencyKey
}) {
  const { data } = await idempotentPost(
    '/attendance/scans',
    { qrToken, latitude, longitude, accuracyMeters, capturedAt, confirmEarlyCheckout },
    { idempotencyKey }
  )
  return data
}
