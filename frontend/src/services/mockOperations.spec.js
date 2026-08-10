import { afterEach, describe, expect, it, vi } from 'vitest'

async function loadSelector() {
  vi.resetModules()
  return import('@/services/mockOperations')
}

afterEach(() => {
  vi.unstubAllEnvs()
})

describe('operation-level mock selection', () => {
  it('selects only the exact configured operation', async () => {
    vi.stubEnv('DEV', true)
    vi.stubEnv('VITE_MOCK_OPERATIONS', 'wallet.fetchWallet, worker.scan')
    const { isMockOperationEnabled } = await loadSelector()

    expect(isMockOperationEnabled('wallet.fetchWallet')).toBe(true)
    expect(isMockOperationEnabled('wallet.chargeWallet')).toBe(false)
    expect(isMockOperationEnabled('worker.scan')).toBe(true)
  })

  it('supports an explicit domain wildcard without enabling another domain', async () => {
    vi.stubEnv('DEV', true)
    vi.stubEnv('VITE_MOCK_OPERATIONS', 'documents.*')
    const { isMockOperationEnabled } = await loadSelector()

    expect(isMockOperationEnabled('documents.listDocuments')).toBe(true)
    expect(isMockOperationEnabled('worker.listWorkerWorkCases')).toBe(false)
  })

  it('never selects mocks outside Development', async () => {
    vi.stubEnv('DEV', false)
    vi.stubEnv('VITE_MOCK_OPERATIONS', 'wallet.*,worker.scan')
    const { isMockOperationEnabled } = await loadSelector()

    expect(isMockOperationEnabled('wallet.fetchWallet')).toBe(false)
    expect(isMockOperationEnabled('worker.scan')).toBe(false)
  })
})
