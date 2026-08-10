import * as api from '@/services/api/walletApi'
import { invokeOperation } from '@/services/operationAdapter'

const loadMock = import.meta.env.DEV ? () => import('@/mocks/walletMockApi') : null

function invoke(method, args = []) {
  return invokeOperation({
    operation: `wallet.${method}`,
    api: api[method],
    loadMock,
    mockMethod: method,
    args
  })
}

export function fetchWallet() {
  return invoke('fetchWallet')
}

export function fetchTransactions(params = {}) {
  return invoke('fetchTransactions', [params])
}

export function chargeWallet(payload, options = {}) {
  return invoke('chargeWallet', [payload, options])
}

export function withdrawWallet(payload, options = {}) {
  return invoke('withdrawWallet', [payload, options])
}
