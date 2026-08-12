const wallet = { currency: 'KRW', availableBalance: 1_250_000, lockedBalance: 480_000 }
const transactions = [
  {
    workplaceId: null,
    transactionId: 1,
    type: 'FUNDING',
    amount: 1_160_000,
    direction: 'CREDIT',
    availableAfter: 1_160_000,
    lockedAfter: 0,
    workCaseId: null,
    workTitle: null,
    workplaceName: null,
    displayStatus: 'COMPLETED',
    createdAt: '2026-07-17T05:02:00Z'
  }
]
let nextTransactionId = 2
let nextOrderId = 10
let nextBankTransactionId = 20

export async function fetchWallet() {
  return { ...wallet }
}

export async function fetchTransactions(params = {}) {
  const pageNumber = Math.max(Number(params.page) || 0, 0)
  const size = Math.min(Math.max(Number(params.size) || 20, 1), 100)
  const keyword = String(params.keyword ?? '')
    .trim()
    .toLowerCase()
  const workplaceId =
    params.workplaceId == null || params.workplaceId === '' ? null : Number(params.workplaceId)
  const filtered = transactions
    .filter((transaction) => workplaceId == null || transaction.workplaceId === workplaceId)
    .filter((transaction) => !params.type || transaction.type === params.type)
    .filter((transaction) => !params.from || transaction.createdAt.slice(0, 10) >= params.from)
    .filter((transaction) => !params.to || transaction.createdAt.slice(0, 10) <= params.to)
    .filter((transaction) => params.minAmount == null || transaction.amount >= +params.minAmount)
    .filter((transaction) => params.maxAmount == null || transaction.amount <= +params.maxAmount)
    .filter(
      (transaction) =>
        !keyword ||
        [transaction.workTitle, transaction.workplaceName].some((value) =>
          String(value ?? '')
            .toLowerCase()
            .includes(keyword)
        )
    )
    .sort((left, right) => {
      if (params.sort === 'OLDEST') return left.createdAt.localeCompare(right.createdAt)
      if (params.sort === 'AMOUNT_ASC') return left.amount - right.amount
      if (params.sort === 'AMOUNT_DESC') return right.amount - left.amount
      return right.createdAt.localeCompare(left.createdAt)
    })
  return {
    content: filtered.slice(pageNumber * size, pageNumber * size + size).map((transaction) => {
      const item = { ...transaction }
      Reflect.deleteProperty(item, 'workplaceId')
      return item
    }),
    page: {
      number: pageNumber,
      size,
      totalElements: filtered.length,
      totalPages: filtered.length === 0 ? 0 : Math.ceil(filtered.length / size)
    }
  }
}

export async function chargeWallet({ pin, amount }) {
  if (pin !== '0000') {
    const error = new Error('계좌를 사용할 수 없습니다.')
    error.code = 'FORBIDDEN'
    error.response = {
      status: 403,
      data: { code: error.code, message: error.message, fieldErrors: [] }
    }
    throw error
  }
  const normalizedAmount = Number(amount)
  wallet.availableBalance += normalizedAmount
  transactions.unshift({
    workplaceId: null,
    transactionId: nextTransactionId++,
    type: 'FUNDING',
    amount: normalizedAmount,
    direction: 'CREDIT',
    availableAfter: wallet.availableBalance,
    lockedAfter: wallet.lockedBalance,
    workCaseId: null,
    workTitle: null,
    workplaceName: null,
    displayStatus: 'COMPLETED',
    createdAt: new Date().toISOString()
  })
  return {
    fundingOrderId: nextOrderId++,
    status: 'COMPLETED',
    bankTransactionId: nextBankTransactionId++
  }
}

export async function withdrawWallet({ amount }) {
  const normalizedAmount = Number(amount)
  wallet.availableBalance -= normalizedAmount
  transactions.unshift({
    workplaceId: null,
    transactionId: nextTransactionId++,
    type: 'WITHDRAWAL',
    amount: normalizedAmount,
    direction: 'DEBIT',
    availableAfter: wallet.availableBalance,
    lockedAfter: wallet.lockedBalance,
    workCaseId: null,
    workTitle: null,
    workplaceName: null,
    displayStatus: 'COMPLETED',
    createdAt: new Date().toISOString()
  })
  return {
    withdrawalRequestId: nextOrderId++,
    status: 'COMPLETED',
    bankTransactionId: nextBankTransactionId++
  }
}
