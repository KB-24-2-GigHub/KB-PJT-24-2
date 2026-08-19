<script setup>
/**
 * 처리 중 전체화면 로딩 호스트 — ui 스토어의 loading 상태를 렌더한다.
 * App.vue 에 한 번만 배치한다. 표시는 어디서든
 * `useUiStore().startLoading('메시지')` / `.stopLoading()`.
 *
 * 실제 은행 앱처럼 처리가 끝날 때까지 뒤 화면 조작을 막는다(닫기 버튼 없음, 딤 클릭 무시).
 * 최소 노출 시간 보장은 ui 스토어(stopLoading)가 맡는다 — 화면(라우트) 자체가
 * router.replace/back 으로 곧장 unmount 돼도 이 컴포넌트는 App.vue 에 고정돼 있어
 * 영향받지 않는다.
 */
import { storeToRefs } from 'pinia'

import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
const { loading, loadingMessage } = storeToRefs(ui)
</script>

<template>
  <Teleport to="body">
    <Transition name="loading-overlay">
      <div v-if="loading" class="loading-overlay" role="status" aria-live="polite">
        <div class="spinner" aria-hidden="true"></div>
        <p class="message">{{ loadingMessage }}</p>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.loading-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-loading);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-lg);
  background: var(--color-overlay);
}
.spinner {
  width: 36px;
  height: 36px;
  /* 어두운 딤 배경 위에서 도는 것이 보여야 하므로 고리는 반투명 흰색, 도는 호는
     불투명 흰색으로 대비를 준다. */
  border: 3px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  border-radius: var(--radius-pill);
  animation: spin 0.7s linear infinite;
}
.message {
  font-size: var(--text-md);
  font-weight: var(--weight-medium);
  color: var(--color-on-primary);
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.loading-overlay-enter-active,
.loading-overlay-leave-active {
  transition: opacity 0.15s ease;
}
.loading-overlay-enter-from,
.loading-overlay-leave-to {
  opacity: 0;
}
</style>
