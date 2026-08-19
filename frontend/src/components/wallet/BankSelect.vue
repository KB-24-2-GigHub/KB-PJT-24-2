<script setup>
/**
 * 은행 선택 그리드 — 충전·출금 공용.
 * 승인된 canonical bankCode 20종(@/utils/constants BANKS)만 노출하며, 사용자에게는
 * 이름을 보여주고 API에는 숫자 bankCode를 보낸다.
 *
 * v-model 은 은행 코드(BANKS_ALL[].code) 문자열이다.
 *
 * 20개를 한 번에 다 보여주면 화면이 길어져 처음엔 VISIBLE_COUNT 개만 노출하고
 * "더보기"를 눌러야 전체를 펼친다.
 */
import { computed, ref } from 'vue'
import { ChevronDown, ChevronUp, Landmark } from 'lucide-vue-next'

import { BANKS } from '@/utils/constants'

const VISIBLE_COUNT = 8

defineProps({
  modelValue: { type: String, default: '' },
  label: { type: String, default: '은행' }
})

const emit = defineEmits(['update:modelValue'])

// 로고 로드 실패한 은행 코드 — 해당 은행만 공통 은행 아이콘으로 폴백한다.
const failed = ref(new Set())
function onLogoError(code) {
  failed.value = new Set(failed.value).add(code)
}

const expanded = ref(false)
const visibleBanks = computed(() => (expanded.value ? BANKS : BANKS.slice(0, VISIBLE_COUNT)))

function select(code) {
  emit('update:modelValue', code)
}
</script>

<template>
  <div class="bank-select">
    <p v-if="label" class="label">{{ label }}</p>
    <div class="grid">
      <button
        v-for="bank in visibleBanks"
        :key="bank.code"
        type="button"
        class="bank"
        :class="{ 'is-active': modelValue === bank.code }"
        :aria-pressed="modelValue === bank.code"
        @click="select(bank.code)"
      >
        <img
          v-if="bank.logo && !failed.has(bank.code)"
          :src="bank.logo"
          :alt="`${bank.name} 로고`"
          class="logo"
          @error="onLogoError(bank.code)"
        />
        <span v-else class="dot" :style="{ background: bank.chip }">
          <Landmark :size="14" />
        </span>
        <span class="name">{{ bank.name }}</span>
      </button>
    </div>

    <button
      v-if="BANKS.length > VISIBLE_COUNT"
      type="button"
      class="more"
      @click="expanded = !expanded"
    >
      {{ expanded ? '접기' : '은행 더보기' }}
      <component :is="expanded ? ChevronUp : ChevronDown" :size="16" />
    </button>
  </div>
</template>

<style scoped>
.label {
  margin-bottom: var(--space-sm);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-sub);
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-sm);
}
.bank {
  display: inline-flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  font-size: var(--text-md);
  color: var(--color-text);
}
.bank.is-active {
  border-color: var(--color-owner);
  background: var(--color-owner-weak);
  font-weight: var(--weight-medium);
}
.logo {
  width: 22px;
  height: 22px;
  flex-shrink: 0;
  object-fit: contain;
  border-radius: var(--radius-xs, 4px);
}
.dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  flex-shrink: 0;
  color: #fff;
  border-radius: var(--radius-pill);
}
.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.more {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  width: 100%;
  margin-top: var(--space-sm);
  padding: var(--space-xs);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-sub);
}
</style>
