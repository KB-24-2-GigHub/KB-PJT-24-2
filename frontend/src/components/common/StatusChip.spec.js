import { mount } from '@vue/test-utils'
import { Clock, Lock } from 'lucide-vue-next'
import { describe, expect, it } from 'vitest'

import StatusChip from '@/components/common/StatusChip.vue'

describe('StatusChip', () => {
  it('escrow HELD는 UNFUNDED와 다른 아이콘을 쓴다', () => {
    const held = mount(StatusChip, { props: { status: 'HELD', kind: 'escrow' } })
    const unfunded = mount(StatusChip, { props: { status: 'UNFUNDED', kind: 'escrow' } })

    expect(held.findComponent(Lock).exists()).toBe(true)
    expect(held.findComponent(Clock).exists()).toBe(false)
    expect(unfunded.findComponent(Clock).exists()).toBe(true)
  })
})
