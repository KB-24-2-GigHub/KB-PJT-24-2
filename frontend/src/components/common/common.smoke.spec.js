import { mount } from '@vue/test-utils'
import { describe, expect, it, vi } from 'vitest'

import AppField from '@/components/common/AppField.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import BaseModal from '@/components/common/BaseModal.vue'
import StatusChip from '@/components/common/StatusChip.vue'
import TrustBadge from '@/components/common/TrustBadge.vue'

/**
 * 공통 UI 키트 스모크 테스트 — 각 컴포넌트가 컴파일·마운트되는지 확인한다.
 * (화면 담당이 신뢰하고 조립할 수 있도록 공용 기반을 그린 상태로 유지)
 */
describe('공통 UI 키트 스모크', () => {
  it('BaseButton — 슬롯 라벨과 variant', () => {
    const w = mount(BaseButton, { props: { variant: 'owner' }, slots: { default: '충전' } })
    expect(w.text()).toContain('충전')
    expect(w.classes()).toContain('btn--owner')
  })

  it('AppField — v-model 입력 이벤트', async () => {
    const w = mount(AppField, { props: { label: '이메일', modelValue: '' } })
    await w.get('input').setValue('a@b.com')
    expect(w.emitted('update:modelValue')?.[0]).toEqual(['a@b.com'])
  })

  it('StatusChip — 상태 라벨 표기', () => {
    const w = mount(StatusChip, { props: { status: 'DRAFT', kind: 'workCase' } })
    expect(w.text()).toContain('수락 전')
  })

  it('TrustBadge — 등급 뱃지 렌더', () => {
    const w = mount(TrustBadge, { props: { role: 'worker', level: 2 } })
    expect(w.find('img').exists()).toBe(true)
  })

  it('TrustBadge — level 0 은 미부여 그림', () => {
    const w = mount(TrustBadge, { props: { role: 'owner', level: 0 } })
    expect(w.find('img').exists()).toBe(true)
  })

  /*
   * 프로덕션에서 prop 검증이 제거되는 것을 전제로 한 런타임 가드다.
   * level 을 빠뜨린 호출이 미부여(0단계)로 위장되면 3단계 사용자가 조용히 오표시된다.
   */
  it.each([
    ['level 누락', { role: 'worker' }],
    ['범위 밖 level', { role: 'worker', level: 4 }],
    ['정수가 아닌 level', { role: 'worker', level: 1.5 }],
    ['알 수 없는 role', { role: 'admin', level: 2 }]
  ])('TrustBadge — %s 은 미부여로 위장하지 않고 아무것도 그리지 않는다', (_label, props) => {
    // prop 경고는 개발 중 신호로 의도된 것이다(프로덕션에서는 사라진다). 여기서는
    // 그 경고가 아니라 "그래도 잘못 그리지는 않는다"는 런타임 가드를 확인한다.
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const w = mount(TrustBadge, { props })
    warn.mockRestore()

    expect(w.find('.trust-badge').exists()).toBe(false)
    expect(w.find('img').exists()).toBe(false)
  })

  it('BaseModal — 닫힘 상태에서 마운트', () => {
    const w = mount(BaseModal, { props: { open: false, title: '확인' } })
    expect(w.exists()).toBe(true)
  })
})
