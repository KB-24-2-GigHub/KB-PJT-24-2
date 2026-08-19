/**
 * 뱃지 레벨 설명 모달(강조 헤드라인 + 💵정의문 + 🪜기준 설명 + 💎레벨 달성 기준 표) 렌더링 계약 테스트.
 * 문턱 수치(누적 10/20/30건, 정상 비율 80/90/100%)는 `trustBadgeLevels.js`가 소유하고
 * 이 모달은 그 값과 role 별 표시 라벨(props.label, 예: '안심정산'/'성실근로')로 표를 조립하기만 한다.
 */
import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import TrustBadgeLevelModal from '@/components/common/TrustBadgeLevelModal.vue'

/**
 * 여기 문구는 실제 서비스 문구를 흉내 낸 테스트 픽스처일 뿐이다. 이 모달은 정의문·기준
 * 설명을 스스로 만들지 않고 props 로 받은 값을 그대로 그리므로, 진짜 문구는
 * `BADGE_TYPE`(constants.js) 한 곳에서만 관리되고 여기와는 동기화 대상이 아니다.
 */
function mountModal(props = {}) {
  return mount(TrustBadgeLevelModal, {
    props: {
      open: true,
      headlineBefore: "우리 매장 신뢰도, '",
      headlineAfter: "'로 증명하세요",
      accentColor: 'var(--color-owner)',
      title: '안심사장',
      label: '안심정산',
      totalLabel: '정산',
      definitionTitle: '💵 안심정산이란?',
      definitionDesc: '임금 분쟁 없이 깔끔하게 완료된 정산 내역이에요.',
      criteriaDesc: [
        '사장님의 정산 건수와 안심정산 비율로 계산되는 매장 신뢰 지표예요.',
        '알바생에게 근무 초대를 보낼 때 사장님의 신뢰 뱃지로 노출돼요.'
      ],
      ...props
    },
    global: { stubs: { teleport: true } }
  })
}

describe('TrustBadgeLevelModal', () => {
  it('헤드라인을 조립하고 "{title} 레벨" 구간만 강조색으로 그린다', () => {
    const wrapper = mountModal()

    expect(wrapper.text()).toContain("우리 매장 신뢰도, '안심사장 레벨'로 증명하세요")

    const highlight = wrapper.find('.headline-highlight')
    expect(highlight.text()).toBe('안심사장 레벨')
    expect(highlight.attributes('style')).toContain('color: var(--color-owner)')
  })

  it('정의문·기준 설명을 순서대로 보여준다', () => {
    const wrapper = mountModal()

    // 1) 정의문(제목+설명) — 맨 위. 검정 텍스트(강조색 아님).
    const definitionTitle = wrapper.find('.definition-title')
    expect(definitionTitle.text()).toBe('💵 안심정산이란?')
    expect(definitionTitle.attributes('style')).toBeUndefined()
    expect(wrapper.find('.definition-desc').text()).toBe(
      '임금 분쟁 없이 깔끔하게 완료된 정산 내역이에요.'
    )

    // 2) 기준·노출 설명(🪜 {title} 레벨이란?) — 문장마다 별도 줄
    expect(wrapper.find('.criteria-title').text()).toBe('🪜 안심사장 레벨이란?')
    const criteriaLines = wrapper.findAll('.criteria-desc').map((p) => p.text())
    expect(criteriaLines).toEqual([
      '사장님의 정산 건수와 안심정산 비율로 계산되는 매장 신뢰 지표예요.',
      '알바생에게 근무 초대를 보낼 때 사장님의 신뢰 뱃지로 노출돼요.'
    ])

    // 순서: 정의문 섹션 → 기준 설명 섹션 → 레벨 달성 기준 섹션
    const sections = wrapper.findAll('section').map((s) => s.classes()[0])
    expect(sections).toEqual(['definition-block', 'criteria-block', 'levels-block'])
  })

  it('💎 레벨 달성 기준을 표로 보여준다', () => {
    const wrapper = mountModal()

    expect(wrapper.find('.levels-title').text()).toBe('💎 레벨 달성 기준')
    expect(wrapper.find('.levels-table').exists()).toBe(true)

    const rows = wrapper.findAll('.levels-table tr').map((row) => row.text())
    // Lv.3(100%)만 "이상"이 붙지 않는다 — 더 높은 값이 있을 수 없어서다.
    expect(rows).toEqual([
      'Lv.3누적 정산 30건 이상 & 안심정산 비율 100%',
      'Lv.2누적 정산 20건 이상 & 안심정산 비율 90% 이상',
      'Lv.1누적 정산 10건 이상 & 안심정산 비율 80% 이상',
      'Lv.0누적 정산 10건 미만 또는 안심정산 비율 80% 미만'
    ])
  })

  it('role이 바뀌면 헤드라인·강조색·label·정의문도 그대로 반영한다', () => {
    const wrapper = mountModal({
      headlineBefore: "나의 신뢰도, '",
      headlineAfter: "'로 증명하세요",
      accentColor: 'var(--color-worker)',
      title: '성실알바',
      label: '성실근로',
      totalLabel: '근로',
      definitionTitle: '👷 성실근로란?',
      definitionDesc: '지각이나 결근 없이 정시에 출퇴근을 마친 근무 내역이에요.',
      criteriaDesc: [
        '누적 근무 건수와 성실근로 비율로 계산되는 알바생 신뢰 지표예요.',
        '지원할 때 사장님에게 나의 신뢰 뱃지로 노출돼요.'
      ]
    })

    expect(wrapper.text()).toContain("나의 신뢰도, '성실알바 레벨'로 증명하세요")
    const highlight = wrapper.find('.headline-highlight')
    expect(highlight.text()).toBe('성실알바 레벨')
    expect(highlight.attributes('style')).toContain('color: var(--color-worker)')

    expect(wrapper.find('.definition-title').text()).toBe('👷 성실근로란?')
    expect(wrapper.find('.criteria-title').text()).toBe('🪜 성실알바 레벨이란?')
    expect(wrapper.findAll('.criteria-desc').map((p) => p.text())).toEqual([
      '누적 근무 건수와 성실근로 비율로 계산되는 알바생 신뢰 지표예요.',
      '지원할 때 사장님에게 나의 신뢰 뱃지로 노출돼요.'
    ])
    expect(wrapper.find('.levels-table tr').text()).toBe(
      'Lv.3누적 근로 30건 이상 & 성실근로 비율 100%'
    )
  })

  it('open이 false면 내용을 그리지 않는다', () => {
    const wrapper = mountModal({ open: false })

    expect(wrapper.find('.levels-table').exists()).toBe(false)
    expect(wrapper.find('.definition-title').exists()).toBe(false)
  })
})
