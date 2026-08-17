import '@/assets/main.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'

import App from '@/App.vue'
import router from '@/router'
import { useAuthStore } from '@/stores/auth'

const app = createApp(App)

const pinia = createPinia()
app.use(pinia)

// G0 — 라우터 가드 실행 전에 세션을 복원한다(GET /api/auth/session).
// 부트스트랩 성공·실패와 무관하게 앱은 마운트한다(실패 시 비로그인 상태로 시작).
const auth = useAuthStore(pinia)
auth.bootstrap().finally(() => {
  app.use(router)
  app.mount('#app')
})
