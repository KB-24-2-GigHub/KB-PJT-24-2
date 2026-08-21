<script setup>
/**
 * 사장 탭 화면 레이아웃 — 상단바 + 본문(RouterView) + 하단 탭.
 * 라우터에서 사장 탭 화면(홈·근태관리·문서함·QR)의 부모로 쓴다.
 * 각 view 는 본문(screen-body 안)만 작성한다 — chrome 은 여기서 담당.
 */
import { RouterView } from 'vue-router'

import AppTopBar from '@/components/common/AppTopBar.vue'
import OwnerBottomNav from '@/components/owner/OwnerBottomNav.vue'
</script>

<template>
  <div class="tab-screen">
    <AppTopBar role="OWNER" />
    <main class="screen-body with-tabbar">
      <RouterView />
    </main>
    <OwnerBottomNav />
  </div>
</template>

<style scoped>
.screen-body {
  padding: var(--space-lg);
  /* 하단 고정 탭바에 마지막 항목이 가리지 않도록 하단 여백을 확보한다.
     전역 .with-tabbar(main.css)는 이 scoped .screen-body[data-v] 의 shorthand padding 에
     특이도로 밀려 무효이므로, 실제 여백은 여기 padding-bottom(longhand)에서 지정한다.
     값은 탭바 높이(약 80px)에 여유 8px를 더한 것으로 .with-tabbar 와 동일하다. */
  padding-bottom: calc(88px + env(safe-area-inset-bottom, 0px));
}
</style>
