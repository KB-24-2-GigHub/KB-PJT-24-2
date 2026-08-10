import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/http', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() },
  idempotentPost: vi.fn()
}))

import http, { idempotentPost } from '@/services/http'
import { listDocuments } from '@/services/api/documentsApi'
import { listNotifications } from '@/services/api/notificationsApi'
import { listReports } from '@/services/api/workCasesApi'
import { listWorkerWorkCases, scan } from '@/services/api/workerApi'

describe('API adapter response shapes', () => {
  beforeEach(() => {
    http.get.mockReset()
    idempotentPost.mockReset()
  })

  it.each([
    ['documents', listDocuments, '/documents', { content: [{ documentId: 1 }] }],
    ['notifications', listNotifications, '/notifications', { content: [], unreadCount: 0 }],
    ['worker work cases', listWorkerWorkCases, '/worker/work-cases', { content: [], totalPages: 0 }]
  ])('unwraps the Axios envelope for %s', async (_name, action, path, payload) => {
    http.get.mockResolvedValue({ data: payload })

    await expect(action()).resolves.toEqual(payload)
    expect(http.get.mock.calls[0][0]).toBe(path)
  })

  it('unwraps the report Page response without changing the facade shape', async () => {
    const payload = { content: [], page: { number: 0, totalPages: 0 } }
    http.get.mockResolvedValue({ data: payload })

    await expect(listReports(7)).resolves.toEqual(payload)
    expect(http.get).toHaveBeenCalledWith('/work-cases/7/disputes')
  })

  it('keeps the dormant attendance adapter aligned with the approved idempotent contract', async () => {
    const payload = {
      result: 'RECORDED',
      workCaseId: 7,
      scanType: 'CHECK_IN',
      recordedAt: '2026-08-07T01:00:05Z'
    }
    idempotentPost.mockResolvedValue({ data: payload })

    await expect(
      scan({
        qrToken: 'signed-token',
        latitude: 37.1234567,
        longitude: 127.1234567,
        accuracyMeters: 18.25,
        capturedAt: '2026-08-07T01:00:00Z',
        confirmEarlyCheckout: false,
        idempotencyKey: 'same-intent-key'
      })
    ).resolves.toEqual(payload)

    expect(idempotentPost).toHaveBeenCalledWith(
      '/attendance/scans',
      {
        qrToken: 'signed-token',
        latitude: 37.1234567,
        longitude: 127.1234567,
        accuracyMeters: 18.25,
        capturedAt: '2026-08-07T01:00:00Z',
        confirmEarlyCheckout: false
      },
      { idempotencyKey: 'same-intent-key' }
    )
  })
})
