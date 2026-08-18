<script setup>
/**
 * 바텀시트 — 화면 하단에서 올라오는 시트.
 * 송금상세 필터·보건증 공유/등록·문의하기·알림 목록 등 라우트 없는 모달에 공용으로 쓴다.
 *
 * 사용:
 *   <BaseBottomSheet :open="open" title="필터" @close="open = false">
 *     ...본문...
 *     <template #footer> ...액션 버튼... </template>
 *   </BaseBottomSheet>
 */
import { X } from 'lucide-vue-next'
import { nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '' }
})

const emit = defineEmits(['close'])

const sheetEl = ref(null)
const titleId = useId()
/** 시트를 연 요소. 닫힐 때 여기로 포커스를 돌려준다. */
const returnFocusEl = ref(null)

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

function focusableItems() {
  if (!sheetEl.value) return []
  return Array.from(sheetEl.value.querySelectorAll(FOCUSABLE))
}

/** 배경 스크롤 잠금. 열린 채로 컴포넌트가 사라져도 잠금이 남지 않게 한 곳에서만 만진다. */
function lockScroll(locked) {
  document.body.style.overflow = locked ? 'hidden' : ''
}

watch(
  () => props.open,
  async (open) => {
    lockScroll(open)
    if (open) {
      returnFocusEl.value = document.activeElement
      await nextTick()
      // 첫 버튼(닫기)이 아니라 시트 자체를 잡는다 — 스크린리더가 '닫기'가 아니라
      // 시트 제목부터 읽게 하려는 것이다.
      sheetEl.value?.focus()
      return
    }
    returnFocusEl.value?.focus?.()
    returnFocusEl.value = null
  },
  { immediate: true }
)

onBeforeUnmount(() => lockScroll(false))

/**
 * aria-modal 을 선언한 이상 Tab 이 시트를 벗어나면 안 된다. jsdom 도 브라우저도 Tab 의
 * 기본 이동은 DOM 순서를 따르므로, 양 끝에서만 가로채 반대편으로 감싼다.
 */
function onKeydown(e) {
  if (e.key === 'Escape') {
    emit('close')
    return
  }
  if (e.key !== 'Tab') return

  const items = focusableItems()
  if (items.length === 0) return

  const first = items[0]
  const last = items[items.length - 1]
  const active = document.activeElement

  if (e.shiftKey && (active === first || active === sheetEl.value)) {
    e.preventDefault()
    last.focus()
  } else if (!e.shiftKey && active === last) {
    e.preventDefault()
    first.focus()
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="sheet">
      <div v-if="open" class="sheet-overlay" @click.self="emit('close')">
        <div
          ref="sheetEl"
          class="sheet"
          role="dialog"
          aria-modal="true"
          :aria-labelledby="titleId"
          tabindex="-1"
          @keydown="onKeydown"
        >
          <header class="sheet-head">
            <h2 :id="titleId" class="sheet-title">{{ title }}</h2>
            <button type="button" class="close" aria-label="닫기" @click="emit('close')">
              <X :size="22" />
            </button>
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
.sheet:focus {
  outline: none;
}
.sheet-title {
  font-size: var(--text-lg);
  font-weight: var(--weight-bold);
  color: var(--color-text);
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
