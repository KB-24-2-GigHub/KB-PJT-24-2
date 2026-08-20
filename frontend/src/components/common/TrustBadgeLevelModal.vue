<script setup>
/**
 * 마이페이지 뱃지 타이틀의 i버튼이 여는 레벨 설명 바텀시트.
 *
 * 문턱 수치(누적 10/20/30건, 정상 비율 80/90/100%)는 `@/constants/trustBadgeLevels`가
 * 소유한다 — "문턱은 서버 소유" 원칙의 예외로, 사용자에게 등급표를 설명하는 용도로만
 * 하드코딩됐다(#432).
 */
import { computed } from 'vue'

import BaseBottomSheet from '@/components/common/BaseBottomSheet.vue'
import { thresholdForLevel, TRUST_BADGE_LEVEL_THRESHOLDS } from '@/constants/trustBadgeLevels'

const props = defineProps({
  open: { type: Boolean, default: false },
  // 시트 상단 헤더(뷰 소유 카피)를 "{before}'{title} 레벨'{after}" 로 조립한다.
  // "'{title} 레벨'" 부분만 강조색으로 그리기 위해 문자열을 셋으로 나눠 받는다.
  // 예: before="우리 매장 신뢰도, '", after="'로 증명하세요".
  headlineBefore: { type: String, required: true },
  headlineAfter: { type: String, required: true },
  title: { type: String, required: true }, // 예: '안심사장'
  // 헤드라인의 "'{title} 레벨'" 강조색. 역할별 포인트 색(--color-owner/--color-worker)을
  // 뷰가 넘긴다 — Teleport로 body에 붙어 profile-card의 CSS 변수를 상속받지 못해서다.
  accentColor: { type: String, default: 'var(--color-owner)' },
  // 문턱표 문구에 쓰는 FE 표시 라벨(예: '안심정산'/'성실근로'). API criterionLabel과 값이
  // 다를 수 있다 — badgeModel.normalLabel을 넘긴다(BADGE_TYPE.normalLabel 소유).
  label: { type: String, required: true },
  // 문턱표의 "누적 {totalLabel} N건" 에 쓰는 역할별 도메인 단어(예: '정산'/'근로').
  // badgeModel.totalLabel(BADGE_TYPE.totalLabel 소유)을 넘긴다.
  totalLabel: { type: String, required: true },
  // 마이페이지 카드에 있던 FE 정의문(BADGE_TYPE.definitionTitle/definitionDesc).
  definitionTitle: { type: String, required: true }, // 예: '💵 안심정산이란?'
  definitionDesc: { type: String, required: true }, // 예: '임금 분쟁 없이 깔끔하게 완료된 정산 내역이에요.'
  // 산정 기준·노출 시점 설명(뷰 소유 문구). 문장을 줄 단위로 나눠 배열로 받는다.
  criteriaDesc: { type: Array, required: true }
})

const emit = defineEmits(['close'])

const lv1 = thresholdForLevel(1)

/**
 * "누적 {totalLabel} X건 이상 & {label} 비율 Y%[ 이상]" 형태의 문턱 표에 그릴 행.
 * 3단계(100%)는 그 이상이 없어 "이상"을 붙이지 않는다. 3단계부터 내림차순, 0단계(미달)는
 * 마지막에 "또는" 문구로 둔다.
 */
const levelRows = computed(() => {
  const rows = [...TRUST_BADGE_LEVEL_THRESHOLDS].reverse().map((t) => {
    const percent =
      t.thresholdPercent >= 100 ? `${t.thresholdPercent}%` : `${t.thresholdPercent}% 이상`
    return {
      level: t.level,
      desc: `누적 ${props.totalLabel} ${t.thresholdCount}건 이상 & ${props.label} 비율 ${percent}`
    }
  })
  rows.push({
    level: 0,
    desc: `누적 ${props.totalLabel} ${lv1.thresholdCount}건 미만 또는 ${props.label} 비율 ${lv1.thresholdPercent}% 미만`
  })
  return rows
})
</script>

<template>
  <BaseBottomSheet :open="open" @close="emit('close')">
    <template #title>
      {{ headlineBefore
      }}<span class="headline-highlight" :style="{ color: accentColor }">{{ title }} 레벨</span
      >{{ headlineAfter }}
    </template>

    <section class="definition-block">
      <p class="definition-title">{{ definitionTitle }}</p>
      <p class="definition-desc">{{ definitionDesc }}</p>
    </section>

    <section class="criteria-block">
      <p class="criteria-title">🪜 {{ title }} 레벨이란?</p>
      <p v-for="line in criteriaDesc" :key="line" class="criteria-desc">{{ line }}</p>
    </section>

    <section class="levels-block">
      <p class="levels-title">💎 레벨 달성 기준</p>
      <table class="levels-table">
        <tbody>
          <tr v-for="row in levelRows" :key="row.level">
            <th scope="row">Lv.{{ row.level }}</th>
            <td>{{ row.desc }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </BaseBottomSheet>
</template>

<style scoped>
/* 헤드라인 위 여백. Teleport로 body에 붙는 BaseBottomSheet 내부 요소라도 scoped 스타일이
   렌더 시점에 이미 부여돼 :deep 로 닿는다 — 다른 BaseBottomSheet 사용처(필터·알림 등)의
   여백은 건드리지 않는다. */
:deep(.sheet-title) {
  padding-top: 8px;
}

.definition-block {
  margin-bottom: var(--space-md);
}
.definition-title {
  font-size: var(--text-sm);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.definition-desc {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}

.criteria-block {
  padding-top: var(--space-md);
  margin-bottom: var(--space-md);
  border-top: 1px solid var(--color-border);
}
.criteria-title {
  font-size: var(--text-sm);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.criteria-desc {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}

.levels-block {
  padding-top: var(--space-md);
  border-top: 1px solid var(--color-border);
}
.levels-title {
  margin-bottom: var(--space-sm);
  font-size: var(--text-sm);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
/* 가로줄만으로는 Lv./설명 두 열이 잘 구분되지 않아 세로줄도 함께 그린 격자 표로 만든다. */
.levels-table {
  width: 100%;
  border: 1px solid var(--color-border);
  border-collapse: collapse;
}
.levels-table th,
.levels-table td {
  padding: var(--space-sm) var(--space-xs);
  border: 1px solid var(--color-border);
  font-size: var(--text-sm);
  text-align: left;
}
/* Lv 열: 좌우 여백을 넓혀 짧은 "Lv.N" 글자가 셀 경계에 바짝 붙지 않게 한다. */
.levels-table th {
  width: 52px;
  padding-left: 19px;
  padding-right: 19px;
  text-align: center;
  font-weight: var(--weight-bold);
  color: var(--color-text);
  background: var(--color-bg);
}
/* 설명 열: 왼쪽 여백을 넓혀 세로줄에 글자가 바짝 붙지 않게 한다(10px → +30%p ≈ 13px). */
.levels-table td {
  padding-left: 13px;
  color: var(--color-text-sub);
}
</style>
