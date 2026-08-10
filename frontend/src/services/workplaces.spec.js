/**
 * 사업장 서비스 계약 테스트.
 * 승인 계약(API_SPEC.md '사업장')은 radius 를 입력으로 받지 않고 서버가 100m 를 적용하며,
 * 주소는 roadAddress·detailAddress 로 분리된다. 목록은 공통 Page Envelope 를 쓴다.
 * 전화번호는 users.phone 과 독립된 원천이지만 같은 정규화 형식을 쓴다.
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/services/http', () => ({
  default: { get: vi.fn(), post: vi.fn(), patch: vi.fn(), delete: vi.fn() }
}))

import http from '@/services/http'
import {
  createWorkplace,
  deleteWorkplace,
  getWorkplaceQr,
  listWorkplaces,
  reissueWorkplaceQr,
  updateWorkplace
} from '@/services/workplaces'

const APPROVED_CREATE_FIELDS = [
  'businessRegistrationNumber',
  'name',
  'representativeName',
  'roadAddress',
  'detailAddress',
  'phone'
]

describe('createWorkplace', () => {
  beforeEach(() => {
    http.post.mockReset().mockResolvedValue({ data: { workplaceId: 7 } })
  })

  it('승인 필드 집합만 보낸다', async () => {
    await createWorkplace({
      businessRegistrationNumber: '1234567890',
      name: '강남점',
      representativeName: '김사장',
      roadAddress: '서울 강남구 테헤란로 1',
      detailAddress: '2층',
      phone: '02-1234-5678'
    })

    const [url, body] = http.post.mock.calls[0]
    expect(url).toBe('/workplaces')
    expect(Object.keys(body).sort()).toEqual([...APPROVED_CREATE_FIELDS].sort())
  })

  it('radius 를 보내지 않는다', async () => {
    await createWorkplace({
      businessRegistrationNumber: '1234567890',
      name: '강남점',
      representativeName: '김사장',
      roadAddress: '서울 강남구 테헤란로 1',
      phone: '0212345678',
      radiusM: 100,
      radiusMeters: 100
    })

    const [, body] = http.post.mock.calls[0]
    expect(body).not.toHaveProperty('radiusM')
    expect(body).not.toHaveProperty('radiusMeters')
  })

  it('전화번호를 구분 문자 없는 숫자로 정규화한다', async () => {
    await createWorkplace({
      businessRegistrationNumber: '1234567890',
      name: '강남점',
      representativeName: '김사장',
      roadAddress: '서울 강남구 테헤란로 1',
      phone: '02-1234-5678'
    })

    expect(http.post.mock.calls[0][1].phone).toBe('0212345678')
  })

  it('사업자등록번호를 구분 문자 없는 숫자로 정규화한다', async () => {
    // 화면(OwnerWorkplaceNewView)은 표시용으로 "123-45-67890" 형태를 만들어 넘긴다.
    // DB 컬럼은 CHAR(10) 이고 CHECK 제약이 숫자 10자리만 허용하므로, 하이픈이 섞인
    // 채로 나가면 등록이 항상 실패한다 — 값 자체를 고정해 키 존재만 보는 얕은 검증을 막는다.
    await createWorkplace({
      businessRegistrationNumber: '123-45-67890',
      name: '강남점',
      representativeName: '김사장',
      roadAddress: '서울 강남구 테헤란로 1',
      phone: '0212345678'
    })

    expect(http.post.mock.calls[0][1].businessRegistrationNumber).toBe('1234567890')
  })

  it('세부주소가 비면 키를 만들지 않는다', async () => {
    await createWorkplace({
      businessRegistrationNumber: '1234567890',
      name: '강남점',
      representativeName: '김사장',
      roadAddress: '서울 강남구 테헤란로 1',
      detailAddress: '   ',
      phone: '0212345678'
    })

    expect(http.post.mock.calls[0][1]).not.toHaveProperty('detailAddress')
  })
})

describe('updateWorkplace', () => {
  beforeEach(() => {
    http.patch.mockReset().mockResolvedValue({ data: { workplaceId: 1 } })
    http.delete.mockReset().mockResolvedValue({ data: undefined })
  })

  it('Backend Operation이 없는 수정 요청은 운영 API를 호출하지 않고 닫힌다', async () => {
    await expect(
      updateWorkplace(1, { name: '강남점', phone: '02-1234-5678' })
    ).rejects.toMatchObject({
      code: 'FEATURE_UNAVAILABLE',
      operation: 'workplaces.updateWorkplace',
      ownerIssue: 'WORKPLACE-002 (담당 구현 이슈 없음)'
    })

    expect(http.patch).not.toHaveBeenCalled()
  })

  it('Deferred 삭제 요청도 운영 API를 호출하지 않고 닫힌다', async () => {
    await expect(deleteWorkplace(1)).rejects.toMatchObject({
      code: 'FEATURE_UNAVAILABLE',
      operation: 'workplaces.deleteWorkplace',
      ownerIssue: 'WORKPLACE-003 (Deferred)'
    })

    expect(http.delete).not.toHaveBeenCalled()
  })
})

describe('listWorkplaces', () => {
  const envelopeFixture = {
    content: [],
    page: { number: 0, size: 100, totalElements: 0, totalPages: 0 }
  }

  beforeEach(() => {
    http.get.mockReset().mockResolvedValue({ data: envelopeFixture })
  })

  it('Page Envelope 를 그대로 반환한다', async () => {
    const result = await listWorkplaces()

    // content/page 가 있는지가 아니라, 서버가 준 Envelope 를 뒤섞지 않고 그대로
    // 통과시키는지를 고정한다 — content/page 를 맞바꿔도 통과하는 얕은 검증을 막는다.
    expect(result).toEqual(envelopeFixture)
  })

  it('승인 Page Query 를 보낸다', async () => {
    await listWorkplaces()

    const [url, config] = http.get.mock.calls[0]
    expect(url).toBe('/workplaces')
    expect(config.params).toEqual({ page: 0, size: 100 })
  })

  it('size 는 승인 최대값을 넘지 않는다', async () => {
    await listWorkplaces({ size: 500 })

    expect(http.get.mock.calls[0][1].params.size).toBe(100)
  })

  it('size 는 승인 최소값보다 작지 않다', async () => {
    await listWorkplaces({ size: 0 })

    expect(http.get.mock.calls[0][1].params.size).toBe(1)
  })
})

/**
 * 고정 QR 계약.
 * 조회는 저장된 nonce 로부터 서버가 매번 같은 토큰을 다시 서명하므로 값이 고정이다.
 * 재발급은 기존 QR 을 즉시 폐기하며 승인 계약이 멱등 Header 를 요구하지 않는다.
 */
describe('사업장 고정 QR', () => {
  beforeEach(() => {
    http.get.mockReset()
    http.post.mockReset()
  })

  it('조회는 승인 경로를 호출하고 payload 를 벗겨 돌려준다', async () => {
    http.get.mockResolvedValue({
      data: { workplaceId: 7, qrToken: 'v1.k1.7.nonce.mac', createdAt: '2026-07-31T00:00:00Z' }
    })

    const qr = await getWorkplaceQr(7)

    expect(http.get).toHaveBeenCalledWith('/workplaces/7/qr')
    expect(qr).toEqual({
      workplaceId: 7,
      qrToken: 'v1.k1.7.nonce.mac',
      createdAt: '2026-07-31T00:00:00Z'
    })
  })

  it('재발급은 Body 없이 POST 하고 멱등 Header 를 붙이지 않는다', async () => {
    http.post.mockResolvedValue({
      data: { workplaceId: 7, qrToken: 'v1.k1.7.new.mac', reissuedAt: '2026-07-31T00:10:00Z' }
    })

    const qr = await reissueWorkplaceQr(7)

    // idempotentPost 로 바꾸면 서버가 요구하지 않는 Idempotency-Key 를 보내게 된다.
    expect(http.post).toHaveBeenCalledWith('/workplaces/7/qr/reissue')
    expect(qr.qrToken).toBe('v1.k1.7.new.mac')
    expect(qr.reissuedAt).toBe('2026-07-31T00:10:00Z')
  })
})
