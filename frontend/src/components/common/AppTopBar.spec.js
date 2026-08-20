/**
 * 지점 select 계약 테스트.
 * 목록에는 INACTIVE 가 함께 오지만 전역 작업 Context 로 선택할 수 있는 것은 ACTIVE
 * 뿐이다(API_SPEC.md:366). INACTIVE 가 옵션에 노출되면 사용자가 고를 수 있게 된다.
 */
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

// 로고 링크의 목적지를 읽으려면 RouterLink 가 필요한데 이 파일은 vue-router 를 통째로
// 대체한다. 실제 RouterLink 대신 to 를 href 로 내보내는 스텁을 끼워 목적지를 검사한다.
const { RouterLinkStub } = vi.hoisted(() => ({
  RouterLinkStub: {
    name: 'RouterLink',
    props: ['to'],
    template: '<a :href="to"><slot /></a>'
  }
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ path: '/owner/attendance' }),
  RouterLink: RouterLinkStub
}))
vi.mock('@/services/workplaces', () => ({ listWorkplaces: vi.fn() }))
// 실제 export 와 이름·개수를 맞춘다. 빠뜨린 export 를 스토어가 호출하면 Vitest 가 던지는데,
// notifications 스토어의 맨몸 catch 와 jsdom 에 EventSource 가 없다는 사정이 겹쳐 지금은
// 조용히 삼켜진다 — 둘 중 하나만 바뀌어도 이 파일이 로고와 무관한 이유로 무너진다.
vi.mock('@/services/notifications', () => ({
  listNotifications: vi.fn().mockResolvedValue({ content: [], page: {} }),
  getUnreadCount: vi.fn().mockResolvedValue({ unreadCount: 0 }),
  markNotificationRead: vi.fn().mockResolvedValue(undefined),
  // jsdom 에 EventSource 가 없어 스토어가 여기까지 오지 않지만, 오게 되더라도 addEventListener
  // 를 가진 객체를 돌려줘야 connect() 가 그 자리에서 터지지 않는다.
  openNotificationStream: vi.fn(() => ({ addEventListener: vi.fn(), close: vi.fn() }))
}))

import AppTopBar from '@/components/common/AppTopBar.vue'
import { useAuthStore } from '@/stores/auth'
import { useWorkplaceStore } from '@/stores/workplace'

describe('AppTopBar 지점 select', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('INACTIVE 사업장은 옵션에 나오지 않는다', async () => {
    useAuthStore().setUser({ name: '김사장', role: 'OWNER', needsWorkplaceSetup: false })
    const workplace = useWorkplaceStore()
    workplace.workplaces = [
      { workplaceId: 1, name: '폐점한 강남점', status: 'INACTIVE' },
      { workplaceId: 2, name: '홍대점', status: 'ACTIVE' }
    ]
    workplace.selectedId = 2
    workplace.loaded = true

    // AppTopBar 의 role 은 부모 레이아웃(OwnerTabLayout)이 정적으로 박아 넣는 필수 prop이라
    // auth 스토어에서 유도되지 않는다 — select 렌더 분기를 타려면 직접 전달해야 한다.
    const wrapper = mount(AppTopBar, { props: { role: 'OWNER' } })

    const optionLabels = wrapper.findAll('option').map((o) => o.text())
    expect(optionLabels).toEqual(['홍대점'])
  })

  /*
   * 닫힌 select 는 폭이 좁아 긴 지점명을 CSS 로 말줄임한다(#275). 잘린 이름을 사용자가
   * 확인할 수 있는 경로가 title 뿐이므로, 말줄임과 title 은 한 쌍으로 유지돼야 한다.
   * (말줄임·화살표 겹침 자체는 CSS 라 jsdom 이 판정하지 못해 브라우저에서 확인했다.)
   */
  it('선택한 지점의 전체 이름을 title 로 노출한다', async () => {
    useAuthStore().setUser({ name: '김사장', role: 'OWNER', needsWorkplaceSetup: false })
    const workplace = useWorkplaceStore()
    workplace.workplaces = [
      { workplaceId: 1, name: '강남점', status: 'ACTIVE' },
      { workplaceId: 2, name: '동대문역사문화공원점', status: 'ACTIVE' }
    ]
    workplace.selectedId = 2
    workplace.loaded = true

    const wrapper = mount(AppTopBar, { props: { role: 'OWNER' } })

    const select = wrapper.get('select')
    expect(select.attributes('title')).toBe('동대문역사문화공원점')
    // title 을 붙이면서 기존 접근성 이름을 덮지 않아야 한다.
    expect(select.attributes('aria-label')).toBe('지점 선택')
  })

  it('ACTIVE 사업장이 하나도 없으면 select 자체를 그리지 않는다', async () => {
    // 픽스처가 항상 ACTIVE 를 하나 이상 포함하면 v-else-if 를 workplaces.length 로
    // 되돌려도 참이 되어 이 회귀를 못 잡는다. ACTIVE 를 0개로 둬 v-else-if 자체를 고정한다.
    useAuthStore().setUser({ name: '김사장', role: 'OWNER', needsWorkplaceSetup: false })
    const workplace = useWorkplaceStore()
    workplace.workplaces = [{ workplaceId: 1, name: '폐점한 강남점', status: 'INACTIVE' }]
    workplace.selectedId = null
    workplace.loaded = true

    const wrapper = mount(AppTopBar, { props: { role: 'OWNER' } })

    expect(wrapper.find('select').exists()).toBe(false)
  })
})

/**
 * 로고 → 역할별 홈 이동 계약(#414).
 *
 * 목적지는 세션의 역할 하나로 갈리므로, 두 역할을 모두 고정하지 않으면 한쪽으로 굳어도
 * 통과한다. 그래서 OWNER·WORKER 를 같은 표로 함께 검사한다.
 */
describe('AppTopBar 로고 홈 이동', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  // 목적지를 정하는 것은 세션 역할이다(auth.homeRoute). role prop 은 로고 색·지점 select
  // 같은 표시 분기에만 쓰인다. 둘을 따로 받아 두 축을 갈라 놓는다.
  function mountBar(sessionRole, propRole = sessionRole) {
    useAuthStore().setUser({
      name: sessionRole === 'OWNER' ? '김사장' : '이알바',
      role: sessionRole,
      needsWorkplaceSetup: false
    })
    // OWNER 는 onMounted 에서 지점 목록을 조회한다. 이 테스트는 지점과 무관하므로
    // 조회를 건너뛰게 두고, 로고 링크만 남긴다.
    useWorkplaceStore().loaded = true
    return mount(AppTopBar, { props: { role: propRole } })
  }

  it.each([
    ['OWNER', '/owner/home'],
    ['WORKER', '/worker/home']
  ])('%s 상단바 로고는 %s 로 이동한다', (role, path) => {
    const brand = mountBar(role).findComponent(RouterLinkStub)

    expect(brand.exists()).toBe(true)
    expect(brand.props('to')).toBe(path)
  })

  /**
   * 위 표는 두 축이 같은 값이라 목적지를 prop 에서 뽑아도 통과한다. Patch 는 목적지가
   * "로그인한 사용자의 역할"만으로 정해진다고 적었으므로, 두 축이 어긋나는 상태로 그
   * 문장을 고정한다. 같은 표가 auth 스토어·가드 G2·G3 와 이미 있어 사본을 더 두면
   * 홈 경로가 바뀔 때 로고만 어긋난다.
   */
  it('레이아웃 prop 이 아니라 세션 역할이 목적지를 정한다', () => {
    const brand = mountBar('WORKER', 'OWNER').findComponent(RouterLinkStub)

    expect(brand.props('to')).toBe('/worker/home')
  })

  /**
   * 계약이 약속한 것은 "링크"다 — 컴포넌트가 아니라 앵커가 있어야 포커스·Enter·링크 역할이
   * 따라온다. RouterLink 를 custom + span 으로 되돌리면 위 목적지 검사는 그대로 통과하고
   * 이 단언만 깨진다. 그래서 태그까지 좁혀 잡는다.
   */
  it('로고를 앵커로 렌더한다', () => {
    const brand = mountBar('OWNER').get('a.brand')

    // 이름이 없으면 스크린리더가 "링크"라고만 읽는다.
    expect(brand.attributes('aria-label')).toBe('GigHub 홈')
  })

  /**
   * 이중 낭독을 막는 것은 링크의 aria-label 이다(ANC 2C — aria-label 이 있으면 서브트리를
   * 순회하지 않는다). 로고 이미지의 aria-hidden 이 막는 것은 그것이 아니라, 링크 안의
   * img(암묵적 role="img") 노드가 browse mode 에서 별도 항목으로 읽히는 것이다.
   */
  it('로고 이미지를 보조기술 트리에서 감춘다', () => {
    expect(mountBar('OWNER').get('.brand-logo').attributes('aria-hidden')).toBe('true')
  })

  /**
   * role 은 표시 분기(로고 색·지점 select)를 가르는데, isOwner 는 'OWNER' 가 아닌 모든 값을
   * WORKER 로 본다. 승인된 집합을 여기에 고정해 두면 validator 를 지우거나 넓혔을 때 걸린다.
   */
  it('role prop 은 승인된 두 값만 받는다', () => {
    const { validator } = AppTopBar.props.role

    expect(['OWNER', 'WORKER'].map(validator)).toEqual([true, true])
    expect(['owner', 'ADMIN', ''].map(validator)).toEqual([false, false, false])
  })
})
