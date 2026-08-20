/**
 * 근무(work_case) Service 계약 테스트 — DRAFT CRUD·요약·목록·초대와
 * #173 OWNER 정산 승인 실연동의 실제 HTTP 경로를 다룬다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/http', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() },
  idempotentPost: vi.fn()
}))

import http, { idempotentPost } from '@/services/http'
import {
  approveCheckOutMissingRefund,
  approveNoShowRefund,
  approveSettlement,
  createInvite,
  createWorkCase,
  deleteWorkCase,
  getWorkCase,
  getWorkCaseSummary,
  listWorkCases,
  reissueInvite,
  updateWorkCase
} from '@/services/workCases'

describe('workCases service', () => {
  beforeEach(() => {
    http.get.mockReset()
    http.post.mockReset()
    http.patch.mockReset()
    http.delete.mockReset()
    idempotentPost.mockReset()
  })

  it('조회한 지점의 근무 요약을 GET summary로 그대로 반환한다', async () => {
    const summary = {
      draft: 1,
      accepted: 0,
      ready: 0,
      inProgress: 2,
      checkOutMissing: 0,
      completed: 3,
      noShow: 0,
      canceled: 0
    }
    http.get.mockResolvedValue({ data: summary })

    const result = await getWorkCaseSummary(7)

    expect(http.get).toHaveBeenCalledWith('/workplaces/7/work-cases/summary')
    expect(result).toEqual(summary)
  })

  it('목록 조회는 data.content/data.page를 그대로 넘겨준다({data} Envelope 해제)', async () => {
    const page = { number: 1, size: 20, totalElements: 25, totalPages: 2 }
    const content = [{ workCaseId: 1, worker: { workerId: 9, name: '이알바' } }]
    http.get.mockResolvedValue({ data: { content, page } })

    const params = { status: 'DRAFT', page: 1, size: 20 }
    const result = await listWorkCases(7, params)

    expect(http.get).toHaveBeenCalledWith('/workplaces/7/work-cases', { params })
    expect(result).toEqual({ content, page })
  })

  it('등록은 승인 7개 필드만 보내고 생성된 workCaseId를 반환한다', async () => {
    http.post.mockResolvedValue({ data: { workCaseId: 42 } })
    const payload = {
      title: '주말 홀 서빙',
      workDate: '2026-08-10',
      startTime: '09:00',
      endTime: '18:00',
      breakMinutes: 60,
      breakPaid: false,
      dailyWage: 90000
    }

    const result = await createWorkCase(7, payload)

    expect(http.post).toHaveBeenCalledWith('/workplaces/7/work-cases', payload)
    expect(result).toEqual({ workCaseId: 42 })
  })

  it('상세 조회는 GET /work-cases/{id}의 data를 그대로 반환한다', async () => {
    const detail = { workCaseId: 42, worker: null, settlement: null }
    http.get.mockResolvedValue({ data: detail })

    const result = await getWorkCase(42)

    expect(http.get).toHaveBeenCalledWith('/work-cases/42')
    expect(result).toEqual(detail)
  })

  // 성공 응답이 204 라 Body 가 없다. 응답에서 값을 꺼내려 하면 어댑터가 주는 빈 Body 형태에
  // 따라 깨질 수 있어, 다른 204 Endpoint(logout·changePassword)와 같이 그대로 await 한다.
  it('수정은 PATCH로 페이로드를 보내고 Body 없는 204 응답에서도 깨지지 않는다', async () => {
    http.patch.mockResolvedValue('')
    const payload = { title: '변경된 제목' }

    await expect(updateWorkCase(42, payload)).resolves.toBeUndefined()

    expect(http.patch).toHaveBeenCalledWith('/work-cases/42', payload)
  })

  it('삭제는 DELETE만 호출하고 응답 Body를 기대하지 않는다', async () => {
    http.delete.mockResolvedValue({})

    await deleteWorkCase(42)

    expect(http.delete).toHaveBeenCalledWith('/work-cases/42')
  })

  it('초대 발급은 POST /invitations의 data를 그대로 반환한다', async () => {
    const response = { inviteUrl: 'https://app/invitations/abc', expiresAt: '2026-08-10T00:00:00Z' }
    http.post.mockResolvedValue({ data: response })

    const result = await createInvite(42)

    expect(http.post).toHaveBeenCalledWith('/work-cases/42/invitations')
    expect(result).toEqual(response)
  })

  // 발급과 재발급은 의미가 다르다 — 재발급은 이전 Token 을 철회하고 교체한다.
  it('재발급은 발급과 다른 reissue 경로를 호출한다', async () => {
    const response = { inviteUrl: 'https://app/invitations/new', expiresAt: '2026-08-10T00:00:00Z' }
    http.post.mockResolvedValue({ data: response })

    const result = await reissueInvite(42)

    expect(http.post).toHaveBeenCalledWith('/work-cases/42/invitations/reissue')
    expect(result).toEqual(response)
  })

  it('정상 지급 승인은 Body 없이 같은 멱등 Key를 전달한다', async () => {
    const response = {
      settlementId: 1,
      status: 'COMPLETED',
      originalEscrowAmount: 90000,
      workerPaidAmount: 80000,
      ownerRefundAmount: 10000,
      deductionAmount: 10000,
      deductionBaseMinutes: 480,
      lateMinutes: 30,
      earlyLeaveMinutes: 15,
      calculationReason: 'CHECKED_OUT',
      calculationVersion: 'ATTENDANCE_V1',
      calculatedAt: '2026-08-13T01:00:00Z',
      completedAt: '2026-08-13T01:00:00Z'
    }
    idempotentPost.mockResolvedValue({ data: response })

    await expect(approveSettlement(42, { idempotencyKey: 'same-payout-intent' })).resolves.toEqual(
      response
    )

    expect(idempotentPost).toHaveBeenCalledWith('/work-cases/42/settlement/approve', undefined, {
      idempotencyKey: 'same-payout-intent'
    })
  })

  it('NO_SHOW 환불 승인은 별도 경로에 Body 없이 같은 멱등 Key를 전달한다', async () => {
    const response = {
      settlementId: 2,
      status: 'REFUNDED',
      originalEscrowAmount: 90000,
      workerPaidAmount: 0,
      ownerRefundAmount: 90000,
      deductionAmount: 90000,
      deductionBaseMinutes: 480,
      lateMinutes: 0,
      earlyLeaveMinutes: 0,
      calculationReason: 'NO_SHOW',
      calculationVersion: 'ATTENDANCE_V1',
      calculatedAt: '2026-08-13T01:00:00Z',
      completedAt: '2026-08-13T01:00:00Z'
    }
    idempotentPost.mockResolvedValue({ data: response })

    await expect(
      approveNoShowRefund(42, { idempotencyKey: 'same-refund-intent' })
    ).resolves.toEqual(response)

    expect(idempotentPost).toHaveBeenCalledWith(
      '/work-cases/42/settlement/no-show-refund/approve',
      undefined,
      { idempotencyKey: 'same-refund-intent' }
    )
  })

  it('CHECK_OUT_MISSING 환불 승인은 NO_SHOW와 구분한 별도 경로를 호출한다', async () => {
    const response = {
      settlementId: 3,
      status: 'REFUNDED',
      originalEscrowAmount: 90000,
      workerPaidAmount: 0,
      ownerRefundAmount: 90000,
      deductionAmount: 90000,
      deductionBaseMinutes: 480,
      lateMinutes: 0,
      earlyLeaveMinutes: 0,
      calculationReason: 'CHECK_OUT_MISSING',
      calculationVersion: 'ATTENDANCE_V1',
      calculatedAt: '2026-08-13T01:00:00Z',
      completedAt: '2026-08-13T01:00:00Z'
    }
    idempotentPost.mockResolvedValue({ data: response })

    await expect(
      approveCheckOutMissingRefund(42, { idempotencyKey: 'same-missing-refund-intent' })
    ).resolves.toEqual(response)

    expect(idempotentPost).toHaveBeenCalledWith(
      '/work-cases/42/settlement/check-out-missing-refund/approve',
      undefined,
      { idempotencyKey: 'same-missing-refund-intent' }
    )
  })
})

describe('workCases service — 미구현 연락처는 fail-closed, 분쟁은 LIVE', () => {
  beforeEach(() => {
    http.get.mockReset()
    http.post.mockReset()
  })

  it('Production 기본 선택은 fake success 대신 명시 Unavailable을 반환한다', async () => {
    const { getOwnerContact } = await import('@/services/workCases')

    await expect(getOwnerContact(1)).rejects.toMatchObject({ code: 'FEATURE_UNAVAILABLE' })

    expect(http.get).not.toHaveBeenCalled()
    expect(http.post).not.toHaveBeenCalled()
  })

  it('분쟁 조회와 생성은 실제 API adapter로 전달한다', async () => {
    const { listReports, createReport } = await import('@/services/workCases')
    http.get.mockResolvedValueOnce({ data: { content: [], page: { totalElements: 0 } } })
    http.post.mockResolvedValueOnce({ data: { reportId: 7 } })

    await expect(listReports(1)).resolves.toMatchObject({ content: [] })
    await expect(
      createReport(1, { title: '임금 확인', content: '약정 일급 미지급' })
    ).resolves.toEqual({ reportId: 7 })

    expect(http.get).toHaveBeenCalledWith('/work-cases/1/disputes')
    expect(http.post).toHaveBeenCalledWith('/work-cases/1/disputes', {
      title: '임금 확인',
      content: '약정 일급 미지급'
    })
  })
})
