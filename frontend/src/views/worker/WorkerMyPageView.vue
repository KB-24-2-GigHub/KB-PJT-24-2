<script setup>
/**
 * [H] 알바생 마이페이지  ·  /worker/mypage  ·  WORKER
 * 성실근로자 뱃지 카드(등급 + 다음 등급 안내 + 서버 진행 설명 + 정의 소자) + 회원정보/비밀번호
 * 변경 진입 + 로그아웃 + 회원 탈퇴.
 * 연계 API: GET /users/me · GET /users/me/badge · DELETE /users/me
 *   →  @/services/users (getMe, deleteMe) · @/composables/useTrustBadge
 * 진입: /worker/mypage/{profile,password}. 공통: TrustBadge.
 */
import { ChevronRight, KeyRound, UserRound } from 'lucide-vue-next'
import { onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import AppBackHeader from '@/components/common/AppBackHeader.vue'
import AppField from '@/components/common/AppField.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import BaseModal from '@/components/common/BaseModal.vue'
import TrustBadge from '@/components/common/TrustBadge.vue'
import { BADGE_STATE, useTrustBadge } from '@/composables/useTrustBadge'
import { PENDING_FEATURES } from '@/constants/pendingFeatures'
import { fieldErrorMap } from '@/services/http'
import { deleteMe, getMe } from '@/services/users'
import { useAuthStore } from '@/stores/auth'
import { useUiStore } from '@/stores/ui'
import { isRequired } from '@/utils/validators'

const router = useRouter()
const authStore = useAuthStore()
const ui = useUiStore()

const me = ref(null)

// 뱃지 값·등급은 전부 서버 소유다. 이 화면은 문턱 숫자를 알지 못한다.
const {
  badge,
  state: badgeState,
  role: badgeRole,
  level: badgeLevel,
  maxLevel: badgeMaxLevel,
  countMetRatioShort,
  nextLevelLabel,
  progressPercent,
  definition: badgeDefinition,
  load: loadBadge
} = useTrustBadge('worker')

const menuItems = [
  { label: '회원정보 변경', to: '/worker/mypage/profile', icon: UserRound },
  { label: '비밀번호 변경', to: '/worker/mypage/password', icon: KeyRound }
]

// #188 구현 전까지 실 Endpoint 는 404 다 — 확인을 막고 준비 중 안내를 보여준다.
// #188 이 머지되면 PENDING_FEATURES 에서 이 항목만 지우면 이 화면은 그대로 복구된다.
const withdrawalPending = PENDING_FEATURES.WITHDRAWAL

const withdrawOpen = ref(false)
const withdrawPassword = ref('')
const withdrawError = ref('')
const withdrawing = ref(false)
const loggingOut = ref(false)

// 내 정보와 뱃지는 서로 독립된 요청이다. 뱃지 조회 하나가 실패해도 프로필 카드 자체는
// 보여줘야 하므로 Promise.all 로 묶어 함께 실패시키지 않는다.
// 뱃지 실패는 loadBadge 안에서 상태로 흡수되므로 여기서 다시 잡지 않는다.
onMounted(async () => {
  try {
    me.value = await getMe()
  } catch {
    // me 가 비어 있으면 프로필 카드 전체가 v-if 로 자연히 숨는다.
  }
  await loadBadge()
})

/**
 * 로그아웃. authStore.logout() 은 서버 호출 결과와 무관하게 로컬 상태를 비우므로,
 * 이 호출이 끝난 시점에 앱은 이미 로그아웃 상태다. 실패해도 화면에 남으면 상태와
 * 어긋나고 다음 이동에서 G1 가드가 어차피 튕겨낸다 — 그래서 이동은 finally 에서 한다.
 * 다만 서버 세션이 살아있을 가능성은 숨기지 않고 오류로 알린다.
 */
async function handleLogout() {
  loggingOut.value = true
  try {
    await authStore.logout()
  } catch (err) {
    ui.toast(err?.response?.data?.message || '로그아웃 요청이 서버에 전달되지 않았어요.', {
      type: 'danger'
    })
  } finally {
    loggingOut.value = false
    router.push('/')
  }
}

function openWithdraw() {
  withdrawPassword.value = ''
  withdrawError.value = ''
  withdrawOpen.value = true
}

async function confirmWithdraw() {
  const check = isRequired(withdrawPassword.value, '비밀번호')
  if (!check.valid) {
    withdrawError.value = check.message
    return
  }

  withdrawing.value = true
  withdrawError.value = ''
  try {
    await deleteMe({ password: withdrawPassword.value })
    withdrawOpen.value = false
    ui.toast('회원 탈퇴가 완료됐어요.', { type: 'success' })
    await authStore.logout()
    router.push('/')
  } catch (err) {
    // 서버가 실제로 지목한 필드에만 사유를 붙인다 — 잔액·진행 근무 등 무관한 사유를
    // 비밀번호 필드 아래 지어내 보여주지 않는다.
    const errors = fieldErrorMap(err)
    if (errors.password) {
      withdrawError.value = errors.password
    } else if (err?.response?.data?.message) {
      ui.toast(err.response.data.message, { type: 'danger' })
    } else {
      ui.toast('탈퇴 처리 중 오류가 발생했어요.', { type: 'danger' })
    }
  } finally {
    withdrawing.value = false
  }
}
</script>

<template>
  <div class="sub-page">
    <AppBackHeader title="마이페이지" />

    <main class="screen-body">
      <!-- 뱃지는 별도 Endpoint 라 me 조회는 성공했는데 뱃지만 실패할 수 있다.
           그 경우에도 프로필 카드 자체는 보여줘야 하므로 뱃지 조각만 따로 게이팅한다. -->
      <section v-if="me" class="profile-card">
        <div class="profile-top">
          <!-- 승인 프로필 응답에 사진 필드가 없어 기본 아이콘만 노출한다. -->
          <span class="avatar">
            <UserRound :size="24" />
          </span>

          <div class="profile-info">
            <p class="profile-name">{{ me.name }}</p>
            <p class="profile-sub">{{ me.loginId }} | {{ me.email }}</p>
          </div>

          <!-- 역할은 응답 badgeType 에서 파생한다 — 화면이 'worker' 를 고정하지 않는다.
               고정하면 TRUST_OWNER 응답이 성실근로 뱃지로 그려진다(과거 Mock 이 그랬다). -->
          <div v-if="badgeState === BADGE_STATE.READY" class="badge-slot">
            <TrustBadge :role="badgeRole" :level="badgeLevel" :size="40" />
          </div>
        </div>

        <p v-if="badgeState === BADGE_STATE.LOADING" class="badge-notice">
          뱃지 정보를 불러오는 중이에요…
        </p>

        <template v-else-if="badgeState === BADGE_STATE.READY">
          <div
            class="bar"
            role="progressbar"
            :aria-valuenow="progressPercent"
            aria-valuemin="0"
            aria-valuemax="100"
          >
            <div class="bar__fill" :style="{ width: progressPercent + '%' }"></div>
          </div>

          <!--
            남은 건수 0 은 두 가지 뜻이다 — 3단계(다음 등급 없음)이거나, 건수는 채웠는데
            정상 비율이 모자란 상태다. 같은 문장으로 묶으면 뒤쪽이 곧 승급할 것처럼 읽힌다.
          -->
          <p class="level-remaining">
            <template v-if="badgeMaxLevel">최고 등급이에요.</template>
            <template v-else-if="countMetRatioShort">
              다음 레벨 {{ nextLevelLabel }} 건수 조건은 채웠어요. 정상 비율이 더 필요해요.
            </template>
            <template v-else>
              다음 레벨 {{ nextLevelLabel }}까지 {{ badge.criterionLabel }}
              {{ badge.remainingToNextLevel }}건 남음
            </template>
          </p>

          <!-- 서버가 계산한 진행 설명문. 화면이 건수·비율을 다시 문장으로 만들지 않는다. -->
          <p class="badge-desc">{{ badge.criterionDesc }}</p>
          <p class="badge-definition">{{ badgeDefinition }}</p>
        </template>

        <p v-else-if="badgeState === BADGE_STATE.FORBIDDEN" class="badge-notice">
          뱃지를 볼 권한이 없어요.
        </p>

        <p
          v-else-if="badgeState === BADGE_STATE.EMPTY || badgeState === BADGE_STATE.MISMATCH"
          class="badge-notice"
        >
          뱃지 정보를 표시할 수 없어요.
        </p>

        <p v-else class="badge-notice">뱃지 정보를 불러오지 못했어요.</p>
      </section>

      <nav class="menu-list">
        <RouterLink v-for="item in menuItems" :key="item.to" :to="item.to" class="menu-item">
          <component :is="item.icon" :size="20" class="menu-item__icon" />
          <span class="menu-item__label">{{ item.label }}</span>
          <ChevronRight :size="18" class="menu-item__chevron" />
        </RouterLink>
      </nav>

      <section class="account-actions">
        <BaseButton variant="secondary" block :disabled="loggingOut" @click="handleLogout">
          로그아웃
        </BaseButton>
        <button type="button" class="withdraw-link" @click="openWithdraw">회원 탈퇴</button>
      </section>
    </main>

    <BaseModal :open="withdrawOpen" title="회원 탈퇴" @close="withdrawOpen = false">
      <p v-if="withdrawalPending" class="pending-notice">회원 탈퇴는 준비 중입니다.</p>
      <p class="withdraw-desc">
        탈퇴하면 되돌릴 수 없어요. 잔액·예치금이 있거나 진행 중인 근무가 있으면 탈퇴할 수 없어요.
      </p>
      <AppField
        v-model="withdrawPassword"
        label="비밀번호"
        type="password"
        placeholder="비밀번호를 입력하세요"
        :error="withdrawError"
      />
      <template #footer>
        <BaseButton variant="secondary" block :disabled="withdrawing" @click="withdrawOpen = false">
          취소
        </BaseButton>
        <BaseButton
          variant="danger"
          block
          :disabled="withdrawing || !!withdrawalPending"
          @click="confirmWithdraw"
        >
          탈퇴하기
        </BaseButton>
      </template>
    </BaseModal>
  </div>
</template>

<style scoped>
.screen-body {
  padding: var(--space-lg);
}

.profile-card {
  margin: var(--space-sm) 0 var(--space-lg);
  padding: var(--space-lg);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}

.profile-top {
  display: flex;
  align-items: center;
  gap: var(--space-md);
}

.avatar {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  overflow: hidden;
  color: var(--color-worker);
  background: var(--color-worker-weak);
  border-radius: var(--radius-pill);
}
.profile-info {
  flex: 1;
  min-width: 0;
}
.profile-name {
  font-size: var(--text-xl);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.profile-sub {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}

.badge-slot {
  flex-shrink: 0;
}

.bar {
  height: 8px;
  margin-top: var(--space-lg);
  overflow: hidden;
  background: var(--color-bg);
  border-radius: var(--radius-pill);
}
.bar__fill {
  height: 100%;
  background: var(--color-worker);
  border-radius: var(--radius-pill);
}

.level-remaining {
  margin-top: var(--space-sm);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text);
}
.badge-desc {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.badge-definition {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.badge-notice {
  margin-top: var(--space-lg);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}

.menu-list {
  display: flex;
  flex-direction: column;
  margin-top: var(--space-xl);
  border-top: 1px solid var(--color-border);
}
.menu-item {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-lg) var(--space-sm);
  border-bottom: 1px solid var(--color-border);
  color: var(--color-text);
}
.menu-item__icon {
  color: var(--color-text-sub);
}
.menu-item__label {
  flex: 1;
  font-size: var(--text-md);
  font-weight: var(--weight-medium);
}
.menu-item__chevron {
  color: var(--color-text-sub);
}

.account-actions {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-md);
  margin-top: var(--space-lg);
}

.withdraw-link {
  display: block;
  width: 100%;
  padding: var(--space-sm) 0;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
  text-align: center;
  text-decoration: underline;
}

.withdraw-desc {
  margin-bottom: var(--space-lg);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}

.pending-notice {
  padding: var(--space-md);
  margin-bottom: var(--space-lg);
  border-radius: var(--radius-sm);
  background: var(--color-warning-bg);
  color: var(--color-warning);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
}
</style>
