<script setup>
import { RouterView } from 'vue-router'

import LoadingOverlay from '@/components/common/LoadingOverlay.vue'
import NotificationModal from '@/components/common/NotificationModal.vue'
import ToastHost from '@/components/common/ToastHost.vue'
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
