<script setup>
/**
 * 다이얼(휠) 피커의 한 컬럼 — 스크롤로 값을 고른다.
 * TimeWheelPicker 가 오전/오후·시·분 세 컬럼을 이걸로 조립한다.
 *
 * 스크롤이 멈추면(120ms) 가장 가까운 항목을 확정해 update:modelValue 를 올리고,
 * 스크롤 중에는 rAF로 가장 가까운 항목만 굵게 표시해 손끝을 따라오는 느낌을 준다.
 */
import { nextTick, onMounted, ref, watch } from 'vue'

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

function indexOfValue(value) {
  const i = props.options.findIndex((o) => o.value === value)
  return i === -1 ? 0 : i
}

function scrollToIndex(index, smooth) {
  const el = scrollerRef.value
  if (!el) return
  programmatic = true
  el.scrollTo({ top: index * props.itemHeight, behavior: smooth ? 'smooth' : 'instant' })
  // smooth 스크롤은 비동기로 여러 scroll 이벤트를 내므로, 이 스크롤이 자기 자신이 건 것임을
  // 알리는 플래그를 다음 프레임에 풀어 이후의 사용자 스크롤과 구분한다.
  requestAnimationFrame(() => {
    programmatic = false
  })
}

function onItemClick(index) {
  activeIndex.value = index
  scrollToIndex(index, true)
  commit(index)
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
      activeIndex.value = Math.min(
        props.options.length - 1,
        Math.max(0, Math.round(el.scrollTop / props.itemHeight))
      )
    })
  }

  clearTimeout(settleTimer)
  settleTimer = setTimeout(() => {
    scrollToIndex(activeIndex.value, true)
    commit(activeIndex.value)
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
      @click="onItemClick(i)"
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
