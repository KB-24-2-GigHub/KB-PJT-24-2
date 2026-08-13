/**
 * 사업장 등록 화면 계약 테스트.
 * needsWorkplaceSetup 은 서버가 요청 시점 DB 로 계산하는 값이다(API_SPEC.md:233).
 * 클라이언트가 등록 성공만 보고 false 로 단정하면, 서버가 아직 true 인 상태에서
 * OWNER 홈으로 보내 G7 가드와 무한 왕복이 생긴다.
 */
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

vi.mock('@/services/workplaces', () => ({
  createWorkplace: vi.fn(),
  listWorkplaces: vi.fn()
}))
vi.mock('@/utils/daumPostcode', () => ({ embedAddressSearch: vi.fn() }))

import { createWorkplace, listWorkplaces } from '@/services/workplaces'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import OwnerWorkplaceNewView from '@/views/owner/workplace/OwnerWorkplaceNewView.vue'

// 화면은 setup 시점의 authStore.needsWorkplaceSetup 을 cameFromForcedSetup 으로 캡처한다.
// 따라서 setUser 는 반드시 mount 보다 먼저 호출해야 한다.
async function fillValidForm(wrapper) {
  const inputs = wrapper.findAll('input')
  // [0] 사업자등록번호 [1] 상호명 [2] 대표자명 [3] 도로명주소 [4] 세부주소 [5] 전화번호
  await inputs[0].setValue('1234567890')
  await inputs[1].setValue('강남점')
  await inputs[2].setValue('김사장')
  await inputs[3].setValue('서울 강남구 테헤란로 1')
  await inputs[4].setValue('2층')
  await inputs[5].setValue('02-1234-5678')
}

describe('OwnerWorkplaceNewView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    push.mockClear()
    createWorkplace.mockReset().mockResolvedValue({ workplaceId: 7 })
    listWorkplaces.mockReset().mockResolvedValue({
      content: [{ workplaceId: 7, name: '강남점', status: 'ACTIVE' }],
      page: { number: 0, size: 100, totalElements: 1, totalPages: 1 }
    })
  })

  it('등록 성공 후 세션을 다시 조회해 서버가 계산한 값을 쓴다', async () => {
    const auth = useAuthStore()
    auth.setUser({ name: '김사장', role: 'OWNER', needsWorkplaceSetup: true })
    const refreshSession = vi
      .spyOn(auth, 'refreshSession')
      .mockResolvedValue({ authenticated: true, role: 'OWNER', needsWorkplaceSetup: false })

    const wrapper = mount(OwnerWorkplaceNewView)
    await fillValidForm(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(refreshSession).toHaveBeenCalledTimes(1)
    expect(push).toHaveBeenCalledWith('/owner/home')
  })

  it('서버가 여전히 설정이 필요하다고 하면 홈으로 보내지 않는다', async () => {
    const auth = useAuthStore()
    auth.setUser({ name: '김사장', role: 'OWNER', needsWorkplaceSetup: true })
    vi.spyOn(auth, 'refreshSession').mockResolvedValue({
      authenticated: true,
      role: 'OWNER',
      needsWorkplaceSetup: true
    })

    const wrapper = mount(OwnerWorkplaceNewView)
    await fillValidForm(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(push).not.toHaveBeenCalledWith('/owner/home')
  })

  it('목록 갱신이 실패해도 등록 성공 흐름은 이어진다', async () => {
    // GET /api/workplaces 는 아직 서버에 없어 404 다(#145 후속). 목록 갱신 실패가
    // 전파되면 등록에 성공하고도 실패 토스트가 뜬다.
    listWorkplaces.mockRejectedValue(new Error('Request failed with status code 404'))
    const auth = useAuthStore()
    auth.setUser({ name: '김사장', role: 'OWNER', needsWorkplaceSetup: true })
    vi.spyOn(auth, 'refreshSession').mockResolvedValue({
      authenticated: true,
      role: 'OWNER',
      needsWorkplaceSetup: false
    })

    const wrapper = mount(OwnerWorkplaceNewView)
    await fillValidForm(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(push).toHaveBeenCalledWith('/owner/home')
  })

  it('세션 재조회가 실패해도 등록 성공 자체는 실패로 보고하지 않는다', async () => {
    // refreshSession 이 일시적으로 실패하면 서버가 계산한 needsWorkplaceSetup 을
    // 확인할 수 없다. 이때 등록 실패로 보고하면(이미 성공한) 등록을 실패로 왜곡하고,
    // 확인 없이 홈으로 보내면 G7 가드가 즉시 되돌리는 무한 루프가 생긴다 — 그래서 화면에
    // 머물러야 한다.
    const auth = useAuthStore()
    auth.setUser({ name: '김사장', role: 'OWNER', needsWorkplaceSetup: true })
    vi.spyOn(auth, 'refreshSession').mockRejectedValue(new Error('network error'))
    const ui = useUiStore()
    const toastSpy = vi.spyOn(ui, 'toast')

    const wrapper = mount(OwnerWorkplaceNewView)
    await fillValidForm(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(createWorkplace).toHaveBeenCalledTimes(1)
    expect(push).not.toHaveBeenCalledWith('/owner/home')
    expect(toastSpy).toHaveBeenCalledWith('사업장을 등록했어요.', { type: 'success' })
    expect(toastSpy).not.toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ type: 'danger' })
    )
  })

  it('등록 Payload 는 승인 필드만 담고 radius 를 보내지 않는다', async () => {
    const auth = useAuthStore()
    auth.setUser({ name: '김사장', role: 'OWNER', needsWorkplaceSetup: true })
    vi.spyOn(auth, 'refreshSession').mockResolvedValue({
      authenticated: true,
      role: 'OWNER',
      needsWorkplaceSetup: false
    })

    const wrapper = mount(OwnerWorkplaceNewView)
    await fillValidForm(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const [payload] = createWorkplace.mock.calls[0]
    expect(Object.keys(payload).sort()).toEqual(
      [
        'businessRegistrationNumber',
        'detailAddress',
        'name',
        'representativeName',
        'roadAddress',
        'phone'
      ].sort()
    )
    expect(payload).not.toHaveProperty('radiusM')
    expect(payload).not.toHaveProperty('address')
    // 좌표는 서버가 주소로 확정한다 — 보내면 400 이다(SPEC-343-01).
    expect(payload).not.toHaveProperty('latitude')
    expect(payload).not.toHaveProperty('longitude')
  })

  /**
   * 주소 확정 실패와 위치 확인 서비스 장애는 사용자가 할 일이 다르다(SPEC-343-01).
   * 하나로 뭉치면 주소를 고쳐야 하는 상황에서 재시도만 반복하게 된다.
   */
  it('주소 확정 실패와 위치 확인 서비스 장애를 다른 안내로 구분한다', async () => {
    const ui = useUiStore()
    const toastSpy = vi.spyOn(ui, 'toast')

    const notResolvable = new Error('rejected')
    notResolvable.code = 'WORKPLACE_ADDRESS_NOT_RESOLVABLE'
    createWorkplace.mockRejectedValueOnce(notResolvable)

    const wrapper = mount(OwnerWorkplaceNewView)
    await fillValidForm(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const [addressMessage, addressOptions] = toastSpy.mock.calls.at(-1)
    expect(addressMessage).toContain('도로명주소')
    expect(addressOptions).toEqual({ type: 'danger' })
    expect(push).not.toHaveBeenCalled()

    const unavailable = new Error('rejected')
    unavailable.code = 'WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE'
    createWorkplace.mockRejectedValueOnce(unavailable)

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const [unavailableMessage, unavailableOptions] = toastSpy.mock.calls.at(-1)
    expect(unavailableMessage).toContain('다시 시도')
    expect(unavailableMessage).not.toBe(addressMessage)
    // 재시도로 풀리는 실패만 warning 이다 — 고칠 것이 있다는 신호와 섞지 않는다.
    expect(unavailableOptions).toEqual({ type: 'warning' })
  })

  it('상호명·대표자명·도로명주소·세부주소는 서버 @Size 제한과 같은 maxlength 를 갖는다', () => {
    // WorkplaceCreateRequest 의 @Size(max=...) 를 그대로 반영한 값이다 — 서버가 바뀌면 같이 바꿔야 한다.
    const wrapper = mount(OwnerWorkplaceNewView)
    const inputs = wrapper.findAll('input')
    // [0] 사업자등록번호 [1] 상호명 [2] 대표자명 [3] 도로명주소 [4] 세부주소 [5] 전화번호
    expect(inputs[1].attributes('maxlength')).toBe('120')
    expect(inputs[2].attributes('maxlength')).toBe('100')
    expect(inputs[3].attributes('maxlength')).toBe('255')
    expect(inputs[4].attributes('maxlength')).toBe('100')
  })

  it('사업자등록번호는 화면 표시 형식(하이픈 포함) 그대로 서비스에 넘긴다', async () => {
    // 화면은 입력 중 자동으로 하이픈을 채운다(formatBusinessNumberInput). 숫자만 남기는
    // 정규화는 서비스 계층(createWorkplace)의 책임이다 — 화면은 하이픈이 섞인 값을
    // 그대로 넘겨야 정상이고, 여기서 값을 미리 정규화해 보내면 서비스의 정규화 여부를
    // 이 테스트가 가려낼 수 없다.
    const auth = useAuthStore()
    auth.setUser({ name: '김사장', role: 'OWNER', needsWorkplaceSetup: true })
    vi.spyOn(auth, 'refreshSession').mockResolvedValue({
      authenticated: true,
      role: 'OWNER',
      needsWorkplaceSetup: false
    })

    const wrapper = mount(OwnerWorkplaceNewView)
    await fillValidForm(wrapper)
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    const [payload] = createWorkplace.mock.calls[0]
    expect(payload.businessRegistrationNumber).toBe('123-45-67890')
  })

  // 실시간 검증(#238): AuthSignupForm 과 같은 패턴 — 필드를 떠나면 형식 오류가 뜨고
  // 값을 고치면 즉시 사라진다. 대표자명(순수 v-model, isRequired 규칙)으로 배선을 고정한다.
  it('대표자명 필드를 비우고 떠나면 오류가 뜨고 채우면 사라진다', async () => {
    const wrapper = mount(OwnerWorkplaceNewView)
    // [0] 사업자등록번호 [1] 상호명 [2] 대표자명 [3] 도로명주소 [4] 세부주소 [5] 전화번호
    const representativeName = wrapper.findAll('input')[2]

    await representativeName.setValue('   ')
    await representativeName.trigger('blur')
    expect(wrapper.text()).toContain('대표자명을(를) 입력해주세요.')

    await representativeName.setValue('김사장')
    expect(wrapper.text()).not.toContain('대표자명을(를) 입력해주세요.')
  })
})
