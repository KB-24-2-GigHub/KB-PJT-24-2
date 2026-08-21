<script setup>
/**
 * 서브 화면 상단 헤더 — 뒤로가기 + 제목(+ 우측 액션 슬롯).
 * 하단 탭이 없는 서브 화면(충전·근무 상세·마이 하위 등)에서 화면 맨 위에 둔다.
 *
 * to 를 주면 그 경로로 이동, 없으면 router.back().
 */
import { ChevronLeft } from 'lucide-vue-next'
import { useRouter } from 'vue-router'

const props = defineProps({
  title: { type: String, default: '' },
  to: { type: [String, Object], default: null }
})

const router = useRouter()

function goBack() {
  if (props.to) router.push(props.to)
  else router.back()
}
</script>

<template>
  <header class="back-header">
    <button type="button" class="back-btn" aria-label="뒤로" @click="goBack">
      <ChevronLeft :size="24" />
    </button>
    <h1 class="title">{{ title }}</h1>
    <div class="action"><slot name="action" /></div>
  </header>
</template>

<style scoped>
.back-header {
  position: sticky;
  top: 0;
  z-index: var(--z-tabbar);
  display: grid;
  grid-template-columns: 40px 1fr 40px;
  align-items: center;
  height: 52px;
  padding: 0 var(--space-sm);
  background: var(--color-surface);
  /* Gig Hub 로고 상단바와 같은 하단 구분 스타일을 사용한다. */
  box-shadow: var(--shadow-card);
}
.back-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text);
}
.title {
  font-size: var(--text-lg);
  font-weight: var(--weight-bold);
  color: var(--color-text);
  text-align: center;
}
.action {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
}
</style>
