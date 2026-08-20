<script setup>
import { ArrowUpRight, Plus } from 'lucide-vue-next'

import { formatKRW } from '@/utils/format'

defineProps({
  availableBalance: { type: Number, default: 0 }
})

defineEmits(['charge', 'withdraw'])
</script>

<template>
  <section class="wallet-card">
    <p class="label">안심지갑 잔액</p>
    <p class="balance">{{ formatKRW(availableBalance) }}</p>

    <div class="actions">
      <button type="button" class="btn" @click="$emit('charge')">
        <Plus :size="18" />
        충전
      </button>
      <button type="button" class="btn" @click="$emit('withdraw')">
        <ArrowUpRight :size="18" />
        출금
      </button>
    </div>
  </section>
</template>

<style scoped>
.wallet-card {
  background: var(--color-owner-weak);
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

.actions {
  display: flex;
  gap: var(--space-sm);
  margin-top: var(--space-xl);
}

.btn {
  flex: 1;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  padding: var(--space-md);
  border-radius: var(--radius-sm);
  background: var(--color-owner);
  color: var(--color-on-primary);
  font-size: var(--text-md);
  font-weight: var(--weight-medium);
}

.btn:active {
  opacity: 0.85;
}
</style>
