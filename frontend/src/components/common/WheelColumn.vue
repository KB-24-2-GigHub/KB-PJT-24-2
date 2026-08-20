<script setup>
/**
 * 다이얼(휠) 피커의 한 컬럼 — 스크롤로 값을 고른다.
 * TimeWheelPicker 가 오전/오후·시·분 세 컬럼을 이걸로 조립한다.
 *
 * activeIndex(굵게 표시되는 항목)가 바뀌는 즉시 commit 해서 update:modelValue 를 올린다
 * — 스크롤 정착까지 기다렸다가 emit 하면, 화면엔 이미 새 값이 보이는데 정착 전에 '확인'을
 * 누르면 emit 은 아직 이전 값이라 화면과 저장값이 어긋난다(#455 리뷰). 스크롤이 멈추면
 * (120ms) 정확한 위치로 스냅만 보정한다 — 값은 이미 commit 돼 있다.
 */
import { nextTick, onMounted, onUnmounted, ref, watch } from 'vue'

const props = defineProps({
  options: { type: Array, required: true }, // [{ label, value }]
  modelValue: { type: [String, Number], required: true },
  itemHeight: { type: Number, default: 44 }
})
const emit = defineEmits(['update:modelValue'])

const scrollerRef = ref(null)
const activeIndex = ref(
  Math.max(
    0,
    props.options.findIndex((o) => o.value === props.modelValue)
  )
)
let settleTimer = null
let rafPending = false
let programmatic = false
let programmaticTimer = null
let scrollEndTarget = null
// scrollend 를 못 쓰는 환경을 위한 안전망 — 실제 애니메이션 소요 시간의 추측치라
// 저사양 기기 등에서 벗어날 수 있다. scrollend 를 쓸 수 있으면 이 타이머는 보조일 뿐이다.
const PROGRAMMATIC_SCROLL_FALLBACK_MS = 600

function indexOfValue(value) {
  const i = props.options.findIndex((o) => o.value === value)
  return i === -1 ? 0 : i
}

function clearProgrammatic() {
  programmatic = false
  clearTimeout(programmaticTimer)
  programmaticTimer = null
  if (scrollEndTarget) {
    scrollEndTarget.removeEventListener('scrollend', clearProgrammatic)
    scrollEndTarget = null
  }
}

function scrollToIndex(index, smooth) {
  const el = scrollerRef.value
  if (!el) return
  clearProgrammatic()
  programmatic = true
  const top = index * props.itemHeight
  // 일부 구형 WebView(및 테스트 환경의 jsdom)는 Element.scrollTo 가 없다 — 있으면 쓰고,
  // 없으면 scrollTop 대입으로 대체한다(애니메이션 없이 바로 이동하지만 값은 맞는다).
  if (typeof el.scrollTo === 'function') {
    el.scrollTo({ top, behavior: smooth ? 'smooth' : 'auto' })
  } else {
    el.scrollTop = top
  }
  if (!smooth) {
    // 즉시 이동이라 애니메이션이 없다 — 다음 tick 에 바로 사용자 스크롤 감지를 재개한다.
    // 여기서 안 풀면 programmatic 이 계속 true 로 남아 onScroll 이 영영 무시된다.
    programmaticTimer = setTimeout(() => {
      programmatic = false
    }, 0)
    return
  }

  // smooth 스크롤은 수백 ms 동안 여러 scroll 이벤트를 낸다 — 이 스크롤이 자기 자신이
  // 건 것임을 알리는 플래그를, 지원하면 실제 종료 이벤트(scrollend)로 정확히 풀고,
  // 아니면 타이머로 넉넉히 기다렸다가 푼다.
  if ('onscrollend' in el) {
    scrollEndTarget = el
    el.addEventListener('scrollend', clearProgrammatic, { once: true })
  }
  programmaticTimer = setTimeout(clearProgrammatic, PROGRAMMATIC_SCROLL_FALLBACK_MS)
}

function onItemClick(index) {
  activeIndex.value = index
  scrollToIndex(index, true)
  commit(index)
}

/** ArrowUp/ArrowDown으로 한 칸씩 옮긴다 — 스크롤 제스처 없이도 키보드만으로 값을 바꿀 수 있다. */
function onKeydown(e, index) {
  if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return
  e.preventDefault()
  const next =
    e.key === 'ArrowDown' ? Math.min(props.options.length - 1, index + 1) : Math.max(0, index - 1)
  if (next === index) return
  onItemClick(next)
  nextTick(() => {
    scrollerRef.value?.querySelectorAll('button')[next]?.focus()
  })
}

function commit(index) {
  const value = props.options[index]?.value
  if (value !== undefined && value !== props.modelValue) emit('update:modelValue', value)
}

function onScroll() {
  if (programmatic) return
  if (!rafPending) {
    rafPending = true
    requestAnimationFrame(() => {
      rafPending = false
      const el = scrollerRef.value
      if (!el) return
      const next = Math.min(
        props.options.length - 1,
        Math.max(0, Math.round(el.scrollTop / props.itemHeight))
      )
      if (next !== activeIndex.value) {
        activeIndex.value = next
        commit(next)
      }
    })
  }

  // 값은 이미 위에서 commit 됐다 — 이 타이머는 스크롤이 멈춘 뒤 항목 중앙으로
  // 정확히 스냅시키는 시각 보정만 한다.
  clearTimeout(settleTimer)
  settleTimer = setTimeout(() => {
    scrollToIndex(activeIndex.value, true)
  }, 120)
}

watch(
  () => props.modelValue,
  (value) => {
    const index = indexOfValue(value)
    if (index === activeIndex.value) return
    activeIndex.value = index
    scrollToIndex(index, false)
  }
)

onMounted(async () => {
  await nextTick()
  scrollToIndex(activeIndex.value, false)
})

onUnmounted(() => {
  clearTimeout(settleTimer)
  clearProgrammatic()
})
</script>

<template>
  <div ref="scrollerRef" class="wheel-col" @scroll="onScroll">
    <div class="wheel-pad" :style="{ height: itemHeight * 2 + 'px' }" />
    <button
      v-for="(opt, i) in options"
      :key="opt.value"
      type="button"
      class="wheel-item"
      :class="{ 'is-active': i === activeIndex }"
      :style="{ height: itemHeight + 'px' }"
      :aria-pressed="i === activeIndex"
      @click="onItemClick(i)"
      @keydown="onKeydown($event, i)"
    >
      {{ opt.label }}
    </button>
    <div class="wheel-pad" :style="{ height: itemHeight * 2 + 'px' }" />
  </div>
</template>

<style scoped>
.wheel-col {
  flex: 1;
  height: 220px;
  overflow-y: auto;
  scroll-snap-type: y mandatory;
  scrollbar-width: none;
  -webkit-overflow-scrolling: touch;
  mask-image: linear-gradient(to bottom, transparent 0, black 35%, black 65%, transparent 100%);
}
.wheel-col::-webkit-scrollbar {
  display: none;
}
.wheel-item {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  scroll-snap-align: center;
  font-size: var(--text-xl);
  font-weight: var(--weight-medium);
  color: var(--color-text-sub);
  transition:
    color 0.15s,
    font-weight 0.15s;
}
.wheel-item.is-active {
  color: var(--color-text);
  font-weight: var(--weight-bold);
}
</style>
