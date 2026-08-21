<script setup>
import { onMounted } from 'vue'
import { RouterView } from 'vue-router'

import LoadingOverlay from '@/components/common/LoadingOverlay.vue'
import NotificationModal from '@/components/common/NotificationModal.vue'
import ToastHost from '@/components/common/ToastHost.vue'
import { setupKeyboardAvoidance } from '@/utils/keyboardAvoidance'

// 전체화면 모드에서 모바일 키보드가 입력창을 가리는 문제 보정(#489) — 앱 전체에 한 번만 등록한다.
onMounted(() => setupKeyboardAvoidance())
</script>

<template>
  <div class="app">
    <RouterView />
  </div>

  <!-- 전역 오버레이 (어느 화면에서나 동작) -->
  <ToastHost />
  <NotificationModal />
  <LoadingOverlay />
</template>
