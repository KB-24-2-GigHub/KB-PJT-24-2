/**
 * 사업장 관리 화면 계약 테스트.
 * 승인 수정 허용 필드는 name, roadAddress, detailAddress, phone 이다(API_SPEC.md:370).
 * businessRegistrationNumber·representativeName·radiusMeters 를 보내면 400 이다.
 */
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/services/workplaces', () => ({
  listWorkplaces: vi.fn(),
  updateWorkplace: vi.fn(),
  deleteWorkplace: vi.fn()
}))
vi.mock('@/services/workCases', () => ({
  getWorkCaseSummary: vi.fn().mockResolvedValue({ inProgress: 0 })
}))
vi.mock('@/utils/daumPostcode', () => ({ embedAddressSearch: vi.fn() }))

import { deleteWorkplace, listWorkplaces, updateWorkplace } from '@/services/workplaces'
import { useUiStore } from '@/stores/ui'
import OwnerWorkplaceManageView from '@/views/owner/workplace/OwnerWorkplaceManageView.vue'

const WORKPLACE = {
  workplaceId: 1,
  businessRegistrationNumber: '1234567890',
  name: '강남점',
  representativeName: '김사장',
  roadAddress: '서울 강남구 테헤란로 1',
  detailAddress: '2층',
  phone: '0212345678',
  radiusMeters: 100,
  status: 'ACTIVE'
}

/** Teleport 를 stub 해 Modal 내용을 wrapper 안에서 찾을 수 있게 한다. */
function mountView() {
  return mount(OwnerWorkplaceManageView, { global: { stubs: { teleport: true } } })
}

function findByText(wrapper, selector, text) {
  const found = wrapper.findAll(selector).find((el) => el.text().trim() === text)
  if (!found) throw new Error(`'${text}' ${selector} 를 찾지 못했습니다`)
  return found
}

/** 첫 사업장의 '수정' 버튼을 눌러 다이얼로그를 연다. */
async function openEditDialog() {
  const wrapper = mountView()
  await flushPromises()
  await findByText(wrapper, 'button', '수정').trigger('click')
  await flushPromises()
  return wrapper
}

describe('OwnerWorkplaceManageView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    listWorkplaces.mockReset().mockResolvedValue({
      content: [{ ...WORKPLACE }],
      page: { number: 0, size: 100, totalElements: 1, totalPages: 1 }
    })
    updateWorkplace.mockReset().mockResolvedValue({ workplaceId: 1 })
    deleteWorkplace.mockReset().mockResolvedValue(undefined)
  })

  it('목록에 도로명과 세부주소를 함께 보여준다', async () => {
    const wrapper = mountView()
    await flushPromises()

    expect(wrapper.find('.wp-address').text()).toContain('서울 강남구 테헤란로 1')
    expect(wrapper.find('.wp-address').text()).toContain('2층')
  })

  it('수정 다이얼로그는 도로명과 세부주소를 분리해 채운다', async () => {
    const wrapper = await openEditDialog()

    const values = wrapper.findAll('input').map((i) => i.element.value)
    expect(values).toContain('서울 강남구 테헤란로 1')
    expect(values).toContain('2층')
  })

  it('상호명·도로명주소·세부주소는 서버 @Size 제한과 같은 maxlength 를 갖는다', async () => {
    // WorkplaceCreateRequest 의 @Size(max=...) 를 그대로 반영한 값이다 — 서버가 바뀌면 같이 바꿔야 한다.
    const wrapper = await openEditDialog()
    const inputs = wrapper.findAll('input')
    // [0] 상호명 [1] 도로명주소 [2] 세부주소(WORKPLACE.detailAddress 가 있어 열려 있다) [3] 전화번호
    expect(inputs[0].attributes('maxlength')).toBe('120')
    expect(inputs[1].attributes('maxlength')).toBe('255')
    expect(inputs[2].attributes('maxlength')).toBe('100')
  })

  it('수정 요청은 승인 허용 필드만 보낸다', async () => {
    const wrapper = await openEditDialog()

    await findByText(wrapper, 'button', '저장').trigger('click')
    await flushPromises()

    const [, body] = updateWorkplace.mock.calls[0]
    expect(Object.keys(body).sort()).toEqual(
      ['detailAddress', 'name', 'phone', 'roadAddress'].sort()
    )
    // 키 집합만으로는 도로명·세부주소가 뒤바뀌어 전송돼도 잡히지 않는다 — 값도 각자 자리에 있는지 확인한다.
    expect(body.name).toBe(WORKPLACE.name)
    expect(body.roadAddress).toBe(WORKPLACE.roadAddress)
    expect(body.detailAddress).toBe(WORKPLACE.detailAddress)
    expect(body.phone).toBe('02-1234-5678') // WORKPLACE.phone('0212345678')이 화면 표시 형식으로 하이픈이 붙는다.
  })

  it('수정 성공 후 목록 갱신이 실패해도 실패로 보고하지 않는다', async () => {
    // GET /workplaces 가 일시적으로 실패해도 PATCH 자체는 이미 성공했다. 두 요청을
    // 같은 try 에 두면 갱신 실패가 수정 실패로 둔갑해 성공 토스트 대신 오류 토스트가 뜬다.
    const wrapper = await openEditDialog()
    const ui = useUiStore()
    const toastSpy = vi.spyOn(ui, 'toast')
    listWorkplaces.mockRejectedValueOnce(new Error('일시적인 네트워크 오류'))

    await findByText(wrapper, 'button', '저장').trigger('click')
    await flushPromises()

    expect(updateWorkplace).toHaveBeenCalledTimes(1)
    expect(toastSpy).toHaveBeenCalledWith('사업장 정보를 수정했어요.', { type: 'success' })
    expect(toastSpy).not.toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ type: 'danger' })
    )
  })

  it('미구현 수정 Operation은 일반 실패가 아니라 준비 중 상태로 안내한다', async () => {
    const wrapper = await openEditDialog()
    const ui = useUiStore()
    const toastSpy = vi.spyOn(ui, 'toast')
    updateWorkplace.mockRejectedValueOnce({ code: 'FEATURE_UNAVAILABLE' })

    await findByText(wrapper, 'button', '저장').trigger('click')
    await flushPromises()

    expect(toastSpy).toHaveBeenCalledWith('사업장 수정은 현재 준비 중인 기능입니다.', {
      type: 'info'
    })
  })

  it('삭제 성공 후 목록 갱신이 실패해도 실패로 보고하지 않는다', async () => {
    const wrapper = mountView()
    await flushPromises()
    const ui = useUiStore()
    const toastSpy = vi.spyOn(ui, 'toast')
    listWorkplaces.mockRejectedValueOnce(new Error('일시적인 네트워크 오류'))

    await findByText(wrapper, 'button', '삭제').trigger('click')
    await flushPromises()
    await findByText(wrapper, 'button', '삭제하기').trigger('click')
    await flushPromises()

    expect(deleteWorkplace).toHaveBeenCalledTimes(1)
    expect(toastSpy).toHaveBeenCalledWith('사업장을 삭제했어요.', { type: 'success' })
    expect(toastSpy).not.toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ type: 'danger' })
    )
  })

  it('Deferred 삭제 Operation은 모달과 토스트에 준비 중 상태를 표시한다', async () => {
    const wrapper = mountView()
    await flushPromises()
    const ui = useUiStore()
    const toastSpy = vi.spyOn(ui, 'toast')
    deleteWorkplace.mockRejectedValueOnce({ code: 'FEATURE_UNAVAILABLE' })

    await findByText(wrapper, 'button', '삭제').trigger('click')
    await flushPromises()
    await findByText(wrapper, 'button', '삭제하기').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('사업장 삭제는 현재 준비 중인 기능입니다.')
    expect(toastSpy).toHaveBeenCalledWith('사업장 삭제는 현재 준비 중인 기능입니다.', {
      type: 'info'
    })
  })

  // 실시간 검증(#238): AuthSignupForm 과 같은 패턴 — 필드를 떠나면 형식 오류가 뜨고
  // 값을 고치면 즉시 사라진다. Teleport 로 렌더되는 다이얼로그 안에서도 배선이 살아있는지 고정한다.
  it('수정 다이얼로그의 상호명 필드를 비우고 떠나면 오류가 뜨고 채우면 사라진다', async () => {
    const wrapper = await openEditDialog()
    // [0] 상호명 [1] 도로명주소 [2] 세부주소(있으면) [3] 전화번호
    const nameInput = wrapper.findAll('input')[0]

    await nameInput.setValue('')
    await nameInput.trigger('blur')
    expect(wrapper.text()).toContain('상호명을(를) 입력해주세요.')

    await nameInput.setValue('새 상호명')
    expect(wrapper.text()).not.toContain('상호명을(를) 입력해주세요.')
  })

  // Finding 2(재검토): 이전 리뷰는 사업장이 하나뿐인 픽스처로는 이 시나리오를 테스트할 수
  // 없다고 판단했지만 틀렸다 — 새는 것은 화면에 보이는 메시지가 아니라 useFieldValidation
  // 내부의 touched 상태이고, 사업장이 하나뿐이어도 같은 사업장을 다시 열면 재현된다.
  // reset() 이 있으면 조용하고, 없으면(아래에서 지워 확인) blur 없이 값만 지워도 즉시
  // 오류가 뜬다 — "떠난 뒤에만 검증한다"는 계약이 이전 세션의 touched 때문에 깨진다.
  it('취소 후 같은 사업장을 다시 열면 이전 세션의 touched 상태가 새지 않는다', async () => {
    const wrapper = await openEditDialog()
    const nameInput = () => wrapper.findAll('input')[0]

    await nameInput().setValue('')
    await nameInput().trigger('blur')
    expect(wrapper.text()).toContain('상호명을(를) 입력해주세요.')

    await findByText(wrapper, 'button', '취소').trigger('click')
    await flushPromises()
    await findByText(wrapper, 'button', '수정').trigger('click')
    await flushPromises()

    // blur 하지 않고 값만 지운다 — 아직 이 세션에서는 필드를 "떠나지" 않았다.
    await nameInput().setValue('')

    expect(wrapper.text()).not.toContain('상호명을(를) 입력해주세요.')
  })
})
