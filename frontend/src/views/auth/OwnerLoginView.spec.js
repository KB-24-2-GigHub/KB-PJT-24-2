import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const push = vi.fn()
vi.mock('vue-router', () => ({
  useRouter: () => ({ push }),
  useRoute: () => ({ query: {} }),
  RouterLink: { props: ['to'], template: '<a><slot /></a>' }
}))

vi.mock('@/utils/fullscreen', () => ({ requestFullscreenSafely: vi.fn() }))

import OwnerLoginView from '@/views/auth/OwnerLoginView.vue'
import { useAuthStore } from '@/stores/auth'
import { requestFullscreenSafely } from '@/utils/fullscreen'

describe('OwnerLoginView', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    push.mockClear()
    requestFullscreenSafely.mockClear()
  })

  it('로그인 요청이 끝나기 전에 이미 전체화면 전환을 시도한다', async () => {
    // requestFullscreen() 은 사용자 클릭 제스처 "안에서, await 이전에 동기적으로" 불러야만
    // 브라우저가 허용한다(#438) — auth.login() 이 아직 끝나지 않은 시점에도 이미 호출돼
    // 있어야 이 계약을 지킨 것이다.
    let resolveLogin
    const login = vi.spyOn(useAuthStore(), 'login').mockReturnValue(
      new Promise((resolve) => {
        resolveLogin = resolve
      })
    )
    const wrapper = mount(OwnerLoginView)

    await wrapper.find('input[placeholder="아이디"]').setValue('owner01')
    await wrapper.find('input[placeholder="비밀번호"]').setValue('secret123')
    await wrapper.find('form').trigger('submit')

    expect(requestFullscreenSafely).toHaveBeenCalledOnce()
    expect(login).toHaveBeenCalledOnce()

    resolveLogin({ needsWorkplaceSetup: false })
    await flushPromises()
  })

  it('아이디·비밀번호를 입력하지 않으면 전체화면으로 전환하지 않는다', async () => {
    const login = vi.spyOn(useAuthStore(), 'login')
    const wrapper = mount(OwnerLoginView)

    await wrapper.find('form').trigger('submit')

    expect(requestFullscreenSafely).not.toHaveBeenCalled()
    expect(login).not.toHaveBeenCalled()
  })
})
