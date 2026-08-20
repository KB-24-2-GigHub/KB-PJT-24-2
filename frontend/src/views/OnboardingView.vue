<script setup>
/**
 * [A] 온보딩  ·  /  ·  PUBLIC
 * 3단계: ① 사장님 소개 ② 알바생 소개 ③ 역할 선택(로그인·회원가입 진입).
 * 로그인·회원가입 화면의 뒤로가기는 `/?step=auth&role=owner|worker`로 이동해 ③을 바로 연다.
 * 로그인 상태로 접근하면 가드(G3)가 역할 홈으로 보낸다.
 */
import { computed, ref } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { Backpack, ChevronLeft, FileText, QrCode, ShieldCheck, Store } from 'lucide-vue-next'

import AuthRoleToggle from '@/components/auth/AuthRoleToggle.vue'
import logoGighubOwner from '@/assets/images/logo/logo-gighub-owner.png'
import logoGighubWorker from '@/assets/images/logo/logo-gighub-worker.png'

const route = useRoute()

const STEP_COUNT = 3
// 로그인·회원가입 화면에서 뒤로가기 시(?step=auth) 역할 선택 단계로 바로 진입한다.
const step = ref(route.query.step === 'auth' ? 2 : 0) // 0: 사장님 소개, 1: 알바생 소개, 2: 역할 선택

const intros = [
  {
    role: 'owner',
    icon: Store,
    title: '사장님, 급여 관리 걱정 끝',
    // 줄바꿈 위치를 고정한다(.tagline 의 white-space: pre-line).
    subtitle: '전자지갑 에스크로로 인건비를 미리 예치하고,\n정산까지 투명하게 관리하세요.',
    features: [
      {
        icon: ShieldCheck,
        title: '안심 에스크로',
        desc: '근무 확정 시 임금을 미리 예치해 체불 걱정을 덜어요.'
      },
      { icon: QrCode, title: 'QR 출퇴근 관리', desc: '매장별 QR로 출퇴근 현황을 한눈에 확인해요.' },
      { icon: FileText, title: '문서 자동 보관', desc: '근로계약서·보건증을 한곳에서 관리해요.' }
    ]
  },
  {
    role: 'worker',
    icon: Backpack,
    title: '알바생, 내 임금은 안전하게',
    subtitle: '일한 만큼 안심하고 받을 수 있는 전자지갑.',
    features: [
      {
        icon: ShieldCheck,
        title: '예치된 임금 확인',
        desc: '사장님이 예치한 안심 금액을 바로 확인해요.'
      },
      { icon: QrCode, title: 'QR 출퇴근', desc: '스캔 한 번으로 출퇴근을 간편하게 인증해요.' },
      { icon: FileText, title: '정산 내역 한눈에', desc: '지급 이력과 문서를 언제든 열람해요.' }
    ]
  }
]

const isIntroStep = computed(() => step.value < intros.length)
const intro = computed(() => intros[step.value])

const role = ref(route.query.role === 'worker' ? 'worker' : 'owner') // 'owner' | 'worker' (역할 선택 단계에서 사용)
const logoGighub = computed(() => (role.value === 'owner' ? logoGighubOwner : logoGighubWorker))

// AuthRoleToggle 은 'OWNER'|'WORKER' 를 쓰고, 여기 role 은 링크 경로(/owner/login)에 그대로 들어가
// 소문자를 유지해야 한다. 그래서 토글과 주고받을 때만 변환한다.
function onSelectRole(next) {
  role.value = next === 'WORKER' ? 'worker' : 'owner'
}

const features = [
  { title: '안심 에스크로', desc: '근무 확정 시 임금을 미리 예치, 정산까지 안전하게.' },
  { title: 'QR 출퇴근', desc: 'QR + GPS 위치로 출퇴근을 간편하게 인증.' },
  { title: '문서·정산 한곳에', desc: '계약서·보건증·정산 이력을 한 화면에서.' }
]
</script>

<template>
  <div class="onboarding">
    <div class="nav-row">
      <button v-if="step > 0" type="button" class="icon-btn" aria-label="이전" @click="step--">
        <ChevronLeft :size="22" />
      </button>
      <span v-else class="icon-btn-spacer" />

      <button v-if="isIntroStep" type="button" class="skip" @click="step = intros.length">
        건너뛰기
      </button>
    </div>

    <template v-if="isIntroStep">
      <div class="hero">
        <component :is="intro.icon" class="hero-icon" :class="`is-${intro.role}`" :size="56" />
        <h1 class="title">{{ intro.title }}</h1>
        <p class="tagline intro-tagline">{{ intro.subtitle }}</p>
      </div>

      <ul class="intro-features">
        <li v-for="f in intro.features" :key="f.title" class="intro-feature">
          <component
            :is="f.icon"
            class="intro-feature-icon"
            :class="`is-${intro.role}`"
            :size="20"
          />
          <div class="intro-feature-text">
            <strong>{{ f.title }}</strong>
            <span>{{ f.desc }}</span>
          </div>
        </li>
      </ul>

      <div class="dots" role="tablist" aria-label="온보딩 단계">
        <span v-for="n in STEP_COUNT" :key="n" class="dot" :class="{ active: n - 1 === step }" />
      </div>

      <button type="button" class="btn btn-primary" @click="step++">다음</button>
    </template>

    <template v-else>
      <div class="hero">
        <img :src="logoGighub" class="logo" alt="Gig Hub" />
        <p class="tagline">전자지갑·에스크로 근로정산 서비스</p>
      </div>

      <AuthRoleToggle
        class="role-select"
        :model-value="role === 'owner' ? 'OWNER' : 'WORKER'"
        @update:model-value="onSelectRole"
      />

      <ul class="features">
        <li v-for="f in features" :key="f.title" class="feature">
          <strong>{{ f.title }}</strong>
          <span>{{ f.desc }}</span>
        </li>
      </ul>

      <div class="cta">
        <RouterLink
          :to="`/${role}/login`"
          class="btn"
          :class="role === 'owner' ? 'btn-owner' : 'btn-worker'"
        >
          로그인
        </RouterLink>
        <RouterLink :to="`/${role}/signup`" class="btn btn-secondary">회원가입</RouterLink>
      </div>
    </template>
  </div>
</template>

<style scoped>
.onboarding {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  padding: var(--space-xl) var(--space-lg);
}
.nav-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 32px;
}
.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  color: var(--color-text-sub);
}
.icon-btn-spacer {
  width: 32px;
  height: 32px;
}
.skip {
  margin-left: auto;
  padding: var(--space-xs) var(--space-sm);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.hero {
  margin-top: var(--space-lg);
  text-align: center;
}
.logo {
  width: 180px;
  height: auto;
  margin: 0 auto;
}
.hero-icon {
  margin: 0 auto;
}
.hero-icon.is-owner {
  color: var(--color-owner);
}
.hero-icon.is-worker {
  color: var(--color-worker);
}
.title {
  margin-top: var(--space-md);
  font-size: var(--text-xl);
  font-weight: var(--weight-medium);
  color: var(--color-text);
}
.tagline {
  margin-top: var(--space-sm);
  color: var(--color-text-sub);
  font-size: var(--text-md);
  white-space: pre-line; /* subtitle 의 \n 을 줄바꿈으로 살린다 */
  word-break: keep-all;
}
/* 사장님/알바생 subtitle 의 줄 수가 달라(1줄 vs 2줄) 아래 목록·버튼이 단계마다
   위아래로 흔들렸다. 두 줄 높이를 항상 예약해 단계 전환 시 위치를 고정한다. */
.intro-tagline {
  min-height: calc(var(--text-md) * 1.5 * 2);
}
.features {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  margin: calc(var(--space-xl) * 1.5) 0 var(--space-xl);
}
.feature {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--space-sm) 0 var(--space-sm) var(--space-md);
  border-bottom: 1px solid var(--color-border);
}
.feature:last-child {
  border-bottom: none;
}
.feature strong {
  font-size: var(--text-md);
  color: var(--color-text);
}
.feature span {
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.intro-features {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  margin: calc(var(--space-xl) * 1.5) 0 var(--space-xl);
}
.intro-feature {
  display: flex;
  align-items: flex-start;
  gap: var(--space-sm);
  padding: var(--space-sm) 0 var(--space-sm) var(--space-md);
  border-bottom: 1px solid var(--color-border);
}
.intro-feature:last-child {
  border-bottom: none;
}
.intro-feature-icon {
  flex-shrink: 0;
  margin-top: 2px;
}
.intro-feature-icon.is-owner {
  color: var(--color-owner);
}
.intro-feature-icon.is-worker {
  color: var(--color-worker);
}
.intro-feature-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.intro-feature-text strong {
  font-size: var(--text-md);
  color: var(--color-text);
}
.intro-feature-text span {
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.dots {
  display: flex;
  justify-content: center;
  gap: var(--space-xs);
  margin-top: auto;
  padding: var(--space-md) 0;
}
.dot {
  width: 6px;
  height: 6px;
  border-radius: var(--radius-pill);
  background: var(--color-border);
}
.dot.active {
  width: 18px;
  background: var(--color-brand);
}
.role-select {
  margin-top: var(--space-lg);
}
.cta {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  margin-top: auto;
}
.btn {
  display: block;
  text-align: center;
  padding: var(--space-md);
  border-radius: var(--radius-sm);
  font-weight: var(--weight-medium);
}
.btn-primary {
  background: var(--color-primary);
  color: var(--color-on-primary);
}
/* 로그인 버튼은 선택한 역할 색으로 채운다(토글 thumb 과 같은 색). */
.btn-owner {
  background: var(--color-owner);
  color: var(--color-on-primary);
  transition: background-color 0.25s ease;
}
.btn-worker {
  background: var(--color-worker);
  color: var(--color-on-primary);
  transition: background-color 0.25s ease;
}
.btn-secondary {
  background: var(--color-surface);
  color: var(--color-text);
  border: 1px solid var(--color-border);
}
</style>
