<script setup>
import { ArrowUpRight } from 'lucide-vue-next'

import { formatKRW } from '@/utils/format'

defineProps({
  availableBalance: { type: Number, default: 0 }
})

defineEmits(['withdraw'])
</script>

<template>
  <section class="wallet-card">
    <p class="label">안심지갑 잔액</p>
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

.label {
  font-size: var(--text-lg);
  color: var(--color-text-sub);
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
