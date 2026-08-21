<script setup>
import { CalendarCheck, FileText, QrCode, Wallet } from 'lucide-vue-next'
import { RouterLink, useRoute } from 'vue-router'

const route = useRoute()

// 사장 하단 탭: 홈(지갑)·근태관리·문서함·QR
const tabs = [
  { key: 'home', label: '홈', icon: Wallet, to: '/owner/home' },
  { key: 'attendance', label: '근태관리', icon: CalendarCheck, to: '/owner/attendance' },
  { key: 'documents', label: '문서함', icon: FileText, to: '/owner/documents' },
  { key: 'qr', label: 'QR', icon: QrCode, to: '/owner/qr' }
]

const isActive = (to) => route.path === to || route.path.startsWith(`${to}/`)
</script>

<template>
  <nav class="bottom-nav" aria-label="사장 메뉴">
    <RouterLink
      v-for="tab in tabs"
      :key="tab.key"
      :to="tab.to"
      class="tab"
      :class="{ 'is-active': isActive(tab.to) }"
    >
      <component :is="tab.icon" :size="22" />
      <span>{{ tab.label }}</span>
    </RouterLink>
  </nav>
</template>

<style scoped>
.bottom-nav {
  position: fixed;
  bottom: 0;
  left: 50%;
  transform: translateX(-50%);
  z-index: var(--z-tabbar);
  width: 100%;
  max-width: 430px;
  display: flex;
  background: var(--color-surface);
  border-radius: 24px 24px 0 0;
  /* 하드 보더 대신 은은한 그림자로 떠 보이게 한다. */
  box-shadow: var(--shadow-sheet);
}
.tab {
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  /* 둥글린 모서리·화면 하단 safe-area 와 칩 사이 여백. */
  margin: 6px 4px calc(6px + env(safe-area-inset-bottom, 0px));
  padding: var(--space-md) 0;
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
/* 선택된 탭 — 연회색 칩으로 살짝 띄워 표시한다.
   칩은 아이콘·글자와 별개인 ::before 로 그린다 — .tab 자체를 scale 하면 배경뿐 아니라
   아이콘·글자까지 함께 줄어들어서(이전 버그) 칩만 사방 5%(양쪽 합 10%, 대략 90% 크기)
   안쪽으로 넣고 둥글린다. */
.tab.is-active {
  color: var(--color-text);
  font-weight: var(--weight-medium);
}
.tab.is-active::before {
  content: '';
  position: absolute;
  inset: 5%;
  z-index: -1;
  background: var(--color-bg);
  border-radius: var(--radius-lg);
}
</style>
