import http, { idempotentPost } from '@/services/http'

export async function fetchWallet() {
  const { data } = await http.get('/wallet')
  return data
}

export async function fetchTransactions(params = {}) {
  const { data } = await http.get('/wallet/transactions', { params })
  return data
}

export async function chargeWallet(
  { bankCode, accountNo, pin, amount },
  { idempotencyKey = null } = {}
) {
  const { data } = await idempotentPost(
    '/wallet/funding-orders',
    { bankCode, accountNo, pin, amount },
    { idempotencyKey }
  )
  return data
}

export async function withdrawWallet(
  { bankCode, accountNo, amount },
  { idempotencyKey = null } = {}
) {
  const { data } = await idempotentPost(
    '/wallet/withdrawal-requests',
    { bankCode, accountNo, amount },
    { idempotencyKey }
  )
  return data
}
