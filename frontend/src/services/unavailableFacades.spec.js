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
    ['attendance scan', () => scan({ qrToken: 'secret' }), '#163-#169'],
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

  it('allows an explicitly selected Development/Test mock for one unavailable operation', async () => {
    isMockOperationEnabled.mockImplementation((operation) => operation === 'worker.scan')

    await expect(scan({ qrToken: 'test-only' })).resolves.toMatchObject({
      scanType: 'CHECK_IN',
      isLate: false
    })
    expect(http.post).not.toHaveBeenCalled()
  })
})
