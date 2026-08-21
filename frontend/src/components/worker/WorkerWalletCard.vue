<script setup>
import { ArrowUpRight, Info } from 'lucide-vue-next'

import { useClickTogglePopover } from '@/composables/useClickTogglePopover'
import { formatKRW } from '@/utils/format'

defineProps({
  availableBalance: { type: Number, default: 0 }
})

defineEmits(['withdraw'])

const { rootEl, open: infoOpen, id: infoId, toggle: toggleInfo } = useClickTogglePopover()
</script>

<template>
  <section ref="rootEl" class="wallet-card">
    <div class="label-row">
      <p class="label">안심지갑 잔액</p>
      <button
        type="button"
        class="info-btn"
        aria-label="안심지갑 잔액 안내"
        :aria-expanded="infoOpen"
        :aria-controls="infoId"
        @click="toggleInfo"
      >
        <Info :size="14" />
      </button>

      <div v-if="infoOpen" :id="infoId" class="balance-info" role="note">
        <p class="balance-info-title">안심지갑 잔액이란?</p>
        <p class="balance-info-body">
          실제로 출금할 수 있는 금액이에요.<br />
          정산이 완료된 급여만 반영되며,<br />
          근무 중이거나 정산 대기 중인 급여는<br />
          아직 포함되지 않아요.
        </p>
      </div>
    </div>
    <p class="balance">{{ formatKRW(availableBalance) }}</p>

    <button type="button" class="btn" @click="$emit('withdraw')">
      <ArrowUpRight :size="18" />
      출금
    </button>
  </section>
</template>

<style scoped>
.wallet-card {
  background: var(--color-worker-weak);
  border-radius: var(--radius-md);
  padding: var(--space-xl);
  box-shadow: var(--shadow-card);
}

.label-row {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--space-xs);
}

.label {
  font-size: var(--text-lg);
  color: var(--color-text-sub);
}

/* 아이콘 자체가 이미 동그란 정보 기호라 별도 배경 원을 씌우지 않는다(원 밖으로 배경이
   삐져나오는 것을 막는다) — held-info(OwnerHomeView)와 같은 방식. */
.info-btn {
  display: inline-flex;
  align-items: center;
  color: var(--color-text-sub);
}

/* 잔액·출금 버튼 위로 겹쳐 뜨되(예치중 팝오버와 같은 오버레이 방식), 오른쪽에 붙여
   내용 길이만큼만 차지한다 — left를 stretch하면 텍스트보다 훨씬 넓어져 오른쪽에 빈
   공간이 남는다. z-index 로 아래 콘텐츠 위에 그려진다. */
.balance-info {
  position: absolute;
  z-index: 1;
  top: 0;
  right: 0;
  width: max-content;
  max-width: 260px;
  padding: var(--space-md);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-card);
}

.balance-info-title {
  font-size: var(--text-md);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}

.balance-info-body {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
  line-height: 1.5;
}

.balance {
  margin-top: var(--space-xs);
  font-size: var(--text-3xl);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}

.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  width: 100%;
  margin-top: var(--space-xl);
  padding: var(--space-md);
  border-radius: var(--radius-sm);
  background: var(--color-worker);
  color: var(--color-on-primary);
  font-size: var(--text-md);
  font-weight: var(--weight-medium);
}

.btn:active {
  opacity: 0.85;
}
</style>
