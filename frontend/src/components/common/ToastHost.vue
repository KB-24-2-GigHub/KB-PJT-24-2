<script setup>
/**
 * 토스트 호스트 — ui 스토어의 토스트 큐를 화면 상단에 렌더한다.
 * App.vue 에 한 번만 배치한다. 표시는 어디서든 `useUiStore().toast('메시지')`.
 */
import { storeToRefs } from 'pinia'

import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
const { toasts } = storeToRefs(ui)
</script>

<template>
  <Teleport to="body">
    <div class="toast-host" aria-live="polite">
      <TransitionGroup name="app-toast">
        <div v-for="t in toasts" :key="t.id" class="app-toast" :class="`app-toast--${t.type}`">
          {{ t.message }}
        </div>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.toast-host {
  position: fixed;
  top: calc(var(--space-lg) + env(safe-area-inset-top, 0px));
  left: 50%;
  transform: translateX(-50%);
  z-index: var(--z-toast);
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  width: 100%;
  max-width: 430px;
  padding: 0 var(--space-lg);
  pointer-events: none;
}
.app-toast {
  align-self: center;
  max-width: 100%;
  padding: var(--space-sm) var(--space-lg);
  border-radius: var(--radius-pill);
  background: var(--color-primary);
  color: var(--color-on-primary);
  font-size: var(--text-sm);
  box-shadow: var(--shadow-card);
}
.app-toast--success {
  background: var(--color-success);
}
.app-toast--warning {
  background: var(--color-warning);
}
.app-toast--danger {
  background: var(--color-danger);
}

.app-toast-enter-active,
.app-toast-leave-active {
  transition: all 0.25s ease;
}
.app-toast-enter-from,
.app-toast-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
