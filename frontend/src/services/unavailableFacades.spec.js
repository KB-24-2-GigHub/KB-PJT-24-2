import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/http', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() },
  idempotentPost: vi.fn()
}))

vi.mock('@/services/mockOperations', () => ({
  isMockOperationEnabled: vi.fn(() => false)
}))

import http, { idempotentPost } from '@/services/http'
import { isMockOperationEnabled } from '@/services/mockOperations'
import { listDocuments } from '@/services/documents'
import { listNotifications } from '@/services/notifications'
import { createReport } from '@/services/workCases'
import { getWorkerHome, scan } from '@/services/worker'

describe('unimplemented public facade operations', () => {
  beforeEach(() => {
    isMockOperationEnabled.mockReturnValue(false)
    vi.clearAllMocks()
  })

  it.each([
    ['worker home', () => getWorkerHome(), '#163-#169'],
    ['documents', () => listDocuments(), '#132/#183'],
    ['notifications', () => listNotifications(), '#167/#176'],
    ['wage dispute', () => createReport(1, { content: '내용' }), '#174-#177']
  ])('fails closed for %s and identifies its owner issue', async (_name, action, ownerIssue) => {
    await expect(action()).rejects.toMatchObject({
      code: 'FEATURE_UNAVAILABLE',
      ownerIssue,
      response: { status: 503 }
    })
    expect(http.get).not.toHaveBeenCalled()
    expect(http.post).not.toHaveBeenCalled()
    expect(http.patch).not.toHaveBeenCalled()
    expect(http.delete).not.toHaveBeenCalled()
    expect(idempotentPost).not.toHaveBeenCalled()
  })

  it('allows an explicitly selected Development/Test mock for one operation', async () => {
    isMockOperationEnabled.mockImplementation((operation) => operation === 'worker.scan')

    await expect(scan({ qrToken: 'test-only' })).resolves.toMatchObject({
      scanType: 'CHECK_IN',
      isLate: false
    })
    expect(http.post).not.toHaveBeenCalled()
    expect(idempotentPost).not.toHaveBeenCalled()
  })

  it('attendance scan (#167) is LIVE and calls the real idempotent API when no mock is selected', async () => {
    idempotentPost.mockResolvedValue({ data: { scanType: 'CHECK_IN', isLate: false } })

    await expect(scan({ qrToken: 'secret', idempotencyKey: 'key-1' })).resolves.toMatchObject({
      scanType: 'CHECK_IN',
      isLate: false
    })
    expect(idempotentPost).toHaveBeenCalledWith(
      '/attendance/scans',
      expect.objectContaining({ qrToken: 'secret' }),
      expect.objectContaining({ idempotencyKey: 'key-1' })
    )
  })
})
