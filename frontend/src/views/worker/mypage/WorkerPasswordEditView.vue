<script setup>
/**
 * [H] 알바생 비밀번호 변경  ·  /worker/mypage/password  ·  WORKER
 * 현재 비밀번호 확인 후 새 비밀번호 설정.
 * 연계 API: PATCH /users/me/password  →  @/services/users (changePassword)
 * 공통: AppField · BaseButton · @/utils/validators
 *
 * 실시간 검증(#238): useFieldValidation 이 형식 오류(errors)를 담당한다. passwordsMatch 는
 * 새 비밀번호와 확인란을 함께 보는 교차 필드 규칙이라 새 비밀번호를 고치면 확인란의
 * 불일치 오류도 자동으로 재검증된다.
 * 제출 실패 시 서버가 지목한 필드 오류는 errors 가 아니라 별도 슬롯(serverErrors)에 담는다.
 * handleSubmit 이 validateAll() 로 이미 모든 필드를 touched 로 올려놓은 뒤라, errors 에
 * 쓰면 관계없는 다른 필드(예: newPassword)를 고치는 순간 재검증 watcher 가 서버가 지목한
 * 필드(예: currentPassword)의 형식 규칙을 다시 평가해(형식은 여전히 유효) 메시지를
 * 지워버린다. serverErrors 는 그 필드 자신의 값이 바뀔 때만 지운다(AuthSignupForm 의
 * checkErrors → serverErrors 일반화와 같은 패턴, #238 Finding 1).
 */
import { reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import AppBackHeader from '@/components/common/AppBackHeader.vue'
import AppField from '@/components/common/AppField.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import { useFieldValidation } from '@/composables/useFieldValidation'
import { fieldErrorMap } from '@/services/http'
import { changePassword } from '@/services/users'
import { useUiStore } from '@/stores/ui'
import { isRequired, passwordRule, passwordsMatch } from '@/utils/validators'

const router = useRouter()
const ui = useUiStore()

const currentPassword = ref('')
const newPassword = ref('')
const newPasswordConfirm = ref('')

// 형식 오류 전용. 규칙은 기존 validate() 가 쓰던 것을 그대로 옮겼다 — 규칙 자체는 불변.
const { errors, handleBlur, validateAll } = useFieldValidation(
  () => ({
    currentPassword: currentPassword.value,
    newPassword: newPassword.value,
    newPasswordConfirm: newPasswordConfirm.value
  }),
  {
    currentPassword: (v) => isRequired(v.currentPassword, '현재 비밀번호'),
    newPassword: (v) => passwordRule(v.newPassword),
    newPasswordConfirm: (v) => passwordsMatch(v.newPassword, v.newPasswordConfirm)
  }
)

const submitting = ref(false)

// 서버 전용 오류 슬롯 — errors 와 같은 키(규칙표의 모든 필드)를 가진다. 그 필드 자신의
// 값이 바뀔 때만 지운다 — 다른 필드를 고쳐도 남아있어야 한다(#238 Finding 1).
const serverErrors = reactive(Object.fromEntries(Object.keys(errors).map((name) => [name, ''])))
const fieldRefs = { currentPassword, newPassword, newPasswordConfirm }
Object.keys(errors).forEach((name) => {
  watch(fieldRefs[name], () => {
    serverErrors[name] = ''
  })
})

async function handleSubmit() {
  if (!validateAll()) return

  submitting.value = true
  try {
    await changePassword({
      currentPassword: currentPassword.value,
      newPassword: newPassword.value
    })
    ui.toast('비밀번호가 변경됐어요.', { type: 'success' })
    router.back()
  } catch (err) {
    // 서버가 실제로 지목한 필드에만 사유를 붙인다 — 네트워크 오류·5xx 를
    // '현재 비밀번호가 일치하지 않아요' 로 단정하지 않는다.
    const fieldErrors = fieldErrorMap(err)
    if (fieldErrors.currentPassword) {
      serverErrors.currentPassword = fieldErrors.currentPassword
    } else if (fieldErrors.newPassword) {
      serverErrors.newPassword = fieldErrors.newPassword
    } else if (err?.response?.data?.message) {
      ui.toast(err.response.data.message, { type: 'danger' })
    } else {
      ui.toast('비밀번호 변경에 실패했어요.', { type: 'danger' })
    }
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="sub-page">
    <AppBackHeader title="비밀번호 변경" />
    <main class="screen-body">
      <form class="edit-form" @submit.prevent="handleSubmit">
        <AppField
          v-model="currentPassword"
          label="현재 비밀번호"
          type="password"
          placeholder="현재 비밀번호를 입력하세요"
          required
          :error="errors.currentPassword || serverErrors.currentPassword"
          @blur="handleBlur('currentPassword')"
        />
        <AppField
          v-model="newPassword"
          label="새 비밀번호"
          type="password"
          placeholder="영문+숫자 포함 8자 이상"
          required
          :error="errors.newPassword || serverErrors.newPassword"
          @blur="handleBlur('newPassword')"
        />
        <AppField
          v-model="newPasswordConfirm"
          label="새 비밀번호 확인"
          type="password"
          placeholder="새 비밀번호를 다시 입력하세요"
          required
          :error="errors.newPasswordConfirm || serverErrors.newPasswordConfirm"
          @blur="handleBlur('newPasswordConfirm')"
        />

        <BaseButton type="submit" variant="worker" block :disabled="submitting"> 변경 </BaseButton>
      </form>
    </main>
  </div>
</template>

<style scoped>
.screen-body {
  padding: var(--space-lg);
}
.edit-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}
</style>
