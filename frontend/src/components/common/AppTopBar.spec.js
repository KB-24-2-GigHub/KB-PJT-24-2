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
vi.mock('@/services/notifications', () => ({
  listNotifications: vi.fn().mockResolvedValue([]),
  markAllRead: vi.fn()
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
    const wrapper = mount(AppTopBar, {
      props: { role: 'OWNER' },
      global: { stubs: { LogoSymbol: true } }
    })

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

    const wrapper = mount(AppTopBar, {
      props: { role: 'OWNER' },
      global: { stubs: { LogoSymbol: true } }
    })

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

    const wrapper = mount(AppTopBar, {
      props: { role: 'OWNER' },
      global: { stubs: { LogoSymbol: true } }
    })

    expect(wrapper.find('select').exists()).toBe(false)
  })
})

/**
 * 로고 → 역할별 홈 이동 계약(#414).
 *
 * 목적지는 role prop 하나로 갈리므로, 두 역할을 모두 고정하지 않으면 한쪽으로 굳어도
 * 통과한다. 그래서 OWNER·WORKER 를 같은 표로 함께 검사한다.
 */
describe('AppTopBar 로고 홈 이동', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function mountBar(role) {
    useAuthStore().setUser({
      name: role === 'OWNER' ? '김사장' : '이알바',
      role,
      needsWorkplaceSetup: false
    })
    // OWNER 는 onMounted 에서 지점 목록을 조회한다. 이 테스트는 지점과 무관하므로
    // 조회를 건너뛰게 두고, 로고 링크만 남긴다.
    useWorkplaceStore().loaded = true
    return mount(AppTopBar, {
      props: { role },
      global: { stubs: { LogoSymbol: true } }
    })
  }

  it.each([
    ['OWNER', '/owner/home'],
    ['WORKER', '/worker/home']
  ])('%s 상단바 로고는 %s 로 이동한다', (role, path) => {
    const brand = mountBar(role).findComponent(RouterLinkStub)

    expect(brand.exists()).toBe(true)
    expect(brand.props('to')).toBe(path)
  })

  it('로고를 링크로 노출하고 이름을 한 번만 준다', () => {
    const wrapper = mountBar('OWNER')

    // 이름이 없으면 스크린리더가 "링크"라고만 읽는다.
    expect(wrapper.get('.brand').attributes('aria-label')).toBe('Gig Hub 홈')
    // logo-symbol.svg 파일 자체가 role="img" aria-label="GigHub symbol" 을 들고 있다.
    // 링크 안에서 그대로 노출되면 이름이 두 번 읽히므로 aria-hidden 으로 덮어야 한다.
    expect(wrapper.get('.brand-logo').attributes('aria-hidden')).toBe('true')
  })
})
