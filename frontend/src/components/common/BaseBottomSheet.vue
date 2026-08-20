<script setup>
/**
 * 바텀시트 — 화면 하단에서 올라오는 시트.
 * 송금상세 필터·보건증 공유/등록·문의하기·알림 목록 등 라우트 없는 모달에 공용으로 쓴다.
 *
 * 사용:
 *   <BaseBottomSheet :open="open" title="필터" @close="open = false">
 *     ...본문...
 *     <template #headerAction> ...제목 옆 보조 동작... </template>
 *     <template #footer> ...액션 버튼... </template>
 *   </BaseBottomSheet>
 *
 * `headerAction` 은 닫기 버튼 왼쪽에 놓인다. 목록 전체에 걸리는 동작(예: 알림 모두 읽음)은
 * 본문과 함께 스크롤되면 안 되므로 헤더에 둔다.
 *
 * 제목 일부에만 강조색을 넣는 등 마크업이 필요하면 `title` prop 대신 `#title` slot을 쓴다
 * (예: TrustBadgeLevelModal). slot이 없으면 `title` prop 텍스트로 대체된다.
 */
import { X } from 'lucide-vue-next'

defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '' }
})

const emit = defineEmits(['close'])
</script>

<template>
  <Teleport to="body">
    <Transition name="sheet">
      <div v-if="open" class="sheet-overlay" @click.self="emit('close')">
        <div class="sheet" role="dialog" aria-modal="true">
          <header class="sheet-head">
            <h2 class="sheet-title">
              <slot name="title">{{ title }}</slot>
            </h2>
            <div class="sheet-actions">
              <slot name="headerAction" />
              <button type="button" class="close" aria-label="닫기" @click="emit('close')">
                <X :size="22" />
              </button>
            </div>
          </header>

          <div class="sheet-body">
            <slot />
          </div>

          <footer v-if="$slots.footer" class="sheet-footer">
            <slot name="footer" />
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.sheet-overlay {
  position: fixed;
  inset: 0;
  z-index: var(--z-overlay);
  display: flex;
  align-items: flex-end;
  justify-content: center;
  background: var(--color-overlay);
}
.sheet {
  width: 100%;
  max-width: 430px;
  max-height: 85vh;
  display: flex;
  flex-direction: column;
  background: var(--color-surface);
  border-radius: var(--radius-lg) var(--radius-lg) 0 0;
  box-shadow: var(--shadow-sheet);
  padding-bottom: env(safe-area-inset-bottom, 0px);
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-lg);
  border-bottom: 1px solid var(--color-border);
}
.sheet-title {
  font-size: var(--text-lg);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.sheet-actions {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}
.close {
  color: var(--color-text-sub);
}
.sheet-body {
  padding: var(--space-lg);
  overflow-y: auto;
}
.sheet-footer {
  padding: var(--space-lg);
  border-top: 1px solid var(--color-border);
}

/* 트랜지션 */
.sheet-enter-active,
.sheet-leave-active {
  transition: opacity 0.2s ease;
}
.sheet-enter-active .sheet,
.sheet-leave-active .sheet {
  transition: transform 0.25s ease;
}
.sheet-enter-from,
.sheet-leave-to {
  opacity: 0;
}
.sheet-enter-from .sheet,
.sheet-leave-to .sheet {
  transform: translateY(100%);
}
</style>
