import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import TaxReferenceCard from '@/components/worker/TaxReferenceCard.vue'

describe('TaxReferenceCard', () => {
  it('서버 참고값과 실제 지급액에서 세금을 차감하지 않는다는 안내를 표시한다', () => {
    const wrapper = mount(TaxReferenceCard, {
      props: {
        taxReference: {
          basisAmount: 200000,
          estimatedTaxAmount: 1480,
          estimatedAfterTaxAmount: 198520
        }
      }
    })

    expect(wrapper.text()).toContain('예상 세액(참고)')
    expect(wrapper.text()).toContain('1,480원')
    expect(wrapper.text()).toContain('198,520원일 수 있어요')
    expect(wrapper.text()).toContain('최종 지급액 200,000원 기준')
    expect(wrapper.text()).toContain('GigHub는 실제 지급액에서 세금을 차감하지 않습니다')
    expect(wrapper.text()).not.toContain('세금 공제')
  })
})
