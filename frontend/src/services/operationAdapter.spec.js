import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/mockOperations', () => ({
  isMockOperationEnabled: vi.fn(() => false)
}))

import { isMockOperationEnabled } from '@/services/mockOperations'
import {
  invokeOperation,
  OperationUnavailableError,
  OPERATION_SUPPORT
} from '@/services/operationAdapter'

describe('operation adapter boundary', () => {
  beforeEach(() => {
    isMockOperationEnabled.mockReturnValue(false)
  })

  it('uses the API adapter for a live operation', async () => {
    const api = vi.fn().mockResolvedValue({ content: [] })

    await expect(
      invokeOperation({ operation: 'wallet.fetchTransactions', api, args: [{ page: 0 }] })
    ).resolves.toEqual({ content: [] })
    expect(api).toHaveBeenCalledWith({ page: 0 })
  })

  it('allows a test to inject an operation mock adapter directly', async () => {
    isMockOperationEnabled.mockReturnValue(true)
    const api = vi.fn()
    const mockMethod = vi.fn().mockResolvedValue({ content: [{ id: 1 }] })
    const loadMock = vi.fn().mockResolvedValue({ list: mockMethod })

    await expect(
      invokeOperation({
        operation: 'documents.listDocuments',
        api,
        loadMock,
        mockMethod: 'list',
        args: [{ workplaceId: 1 }]
      })
    ).resolves.toEqual({ content: [{ id: 1 }] })
    expect(api).not.toHaveBeenCalled()
    expect(mockMethod).toHaveBeenCalledWith({ workplaceId: 1 })
  })

  it('fails closed before calling an API seam for an unavailable operation', async () => {
    const api = vi.fn()

    await expect(
      invokeOperation({
        operation: 'worker.scan',
        support: OPERATION_SUPPORT.UNAVAILABLE,
        ownerIssue: '#169',
        api,
        args: [{ qrToken: 'secret' }]
      })
    ).rejects.toEqual(expect.any(OperationUnavailableError))
    await expect(
      invokeOperation({
        operation: 'worker.scan',
        support: OPERATION_SUPPORT.UNAVAILABLE,
        ownerIssue: '#169',
        api
      })
    ).rejects.toMatchObject({
      code: 'FEATURE_UNAVAILABLE',
      operation: 'worker.scan',
      ownerIssue: '#169',
      response: { status: 503 }
    })
    expect(api).not.toHaveBeenCalled()
  })
})
