<script setup>
/**
 * [A] 사장 로그인  ·  /owner/login  ·  PUBLIC(게스트 전용)
 * 아이디·비밀번호 로그인.
 * 연계 API: POST /auth/login  →  useAuthStore().login({ loginId, password, role: 'OWNER' })
 * 성공 후: needsWorkplaceSetup=true → /owner/workplaces/new,
 *          아니면 route.query.redirect ?? '/owner/home'.
 * 공통: AppField(입력) · BaseButton(제출) · useUiStore().toast(실패 안내)
 */
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import AuthRoleToggle from '@/components/auth/AuthRoleToggle.vue'
import AppBackHeader from '@/components/common/AppBackHeader.vue'
import AppField from '@/components/common/AppField.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import logoGighubOwner from '@/assets/images/logo/logo-gighub-owner.png'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { resolveOwnerLoginRedirect } from '@/utils/authRedirect'
import { requestFullscreenSafely } from '@/utils/fullscreen'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const ui = useUiStore()

const loginId = ref('')
const password = ref('')
const submitting = ref(false)

const canSubmit = computed(() => loginId.value.trim() !== '' && password.value !== '')

function onChangeRole(next) {
  if (next === 'WORKER') router.push('/worker/login')
}

// Schema Gap: 비밀번호 재설정(토큰·메일 인프라) 미구현 → 진입점만 '준비 중'으로 노출(API·라우트 없음).
function onForgotPassword() {
  ui.toast('비밀번호 찾기는 준비 중입니다.', { type: 'info' })
}

async function onSubmit() {
  if (!canSubmit.value) {
    ui.toast('아이디와 비밀번호를 입력해주세요.', { type: 'warning' })
    return
  }

  // 시연용: 전체화면은 사용자 제스처(클릭) 안에서 동기적으로 불러야만 동작한다 —
  // auth.login() 의 await 뒤로 옮기면 브라우저가 조용히 거부한다(#438).
  requestFullscreenSafely()

  submitting.value = true
  try {
    const res = await auth.login({
      loginId: loginId.value,
      password: password.value,
      role: 'OWNER'
    })
    router.push(resolveOwnerLoginRedirect(res, route.query.redirect))
  } catch (err) {
    // 승인 계약상 아이디 없음·비밀번호 불일치·비활성 계정은 모두 401 AUTH_REQUIRED 로
    // 구분되지 않는다. 서버 메시지를 그대로 쓰고 계정 존재 여부를 추론할 문구를 덧붙이지 않는다.
    ui.toast(err?.response?.data?.message || '로그인에 실패했어요.', { type: 'danger' })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="sub-page">
    <AppBackHeader title="사장 로그인" to="/?step=auth&role=owner" />
    <main class="screen-body">
      <img :src="logoGighubOwner" class="logo" alt="Gig Hub" />

      <AuthRoleToggle model-value="OWNER" @update:model-value="onChangeRole" />

      <form class="form" @submit.prevent="onSubmit">
        <AppField v-model="loginId" label="아이디" placeholder="아이디" required />
        <AppField
          v-model="password"
          type="password"
          label="비밀번호"
          placeholder="비밀번호"
          required
        />
        <BaseButton type="submit" variant="owner" size="lg" block :disabled="submitting">
          로그인
        </BaseButton>
      </form>

      <button type="button" class="forgot-link" @click="onForgotPassword">
        비밀번호를 잊으셨나요? <span class="soon">준비 중</span>
      </button>

      <p class="signup-link">
        아직 계정이 없나요?
        <RouterLink to="/owner/signup">회원가입</RouterLink>
      </p>
    </main>
  </div>
</template>

<style scoped>
.screen-body {
  padding: var(--space-lg);
  display: flex;
  flex-direction: column;
  gap: var(--space-xl);
}
.logo {
  width: 180px;
  height: auto;
  margin: var(--space-xs) auto 0;
}
.form {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}
.forgot-link {
  align-self: center;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.forgot-link .soon {
  margin-left: var(--space-xs);
  padding: 0 var(--space-xs);
  border-radius: var(--radius-pill);
  background: var(--color-bg);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.signup-link {
  text-align: center;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.signup-link a {
  color: var(--color-owner);
  font-weight: var(--weight-medium);
}
</style>
