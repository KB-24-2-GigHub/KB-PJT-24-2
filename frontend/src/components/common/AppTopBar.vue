<script setup>
/**
 * 공통 상단바 — 로고 + (사장) 지점 select + 알림 + 마이페이지.
 *
 * 탭 화면 레이아웃(OwnerTabLayout·WorkerTabLayout)이 렌더한다. view 는 본문만 작성한다.
 * - 지점 select: OWNER 전용. workplace 스토어를 원본으로 근태·문서·QR 이 참조한다.
 * - 알림 종: 안읽음 배지 표시 + 클릭 시 notifications 스토어로 알림 모달 열기.
 */
import { Bell, ChevronDown, CircleUser } from 'lucide-vue-next'
import { storeToRefs } from 'pinia'
import { computed, onMounted, onUnmounted } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import logoSymbolOwner from '@/assets/images/logo/logo-symbol-owner.png'
import logoSymbolWorker from '@/assets/images/logo/logo-symbol-worker.png'
import { useAuthStore } from '@/stores/auth'
import { useNotificationsStore } from '@/stores/notifications'
import { useWorkplaceStore } from '@/stores/workplace'

const props = defineProps({
  // 레이아웃이 정적으로 넘기는 값이라 이 둘 외에는 올 자리가 없다. 검증하지 않으면 오타나
  // 새 역할이 조용히 WORKER 분기로 떨어진다 — 가드 G2 가 뒤에서 역할 불일치로 튕겨내지만
  // 원인이 이 prop 이라는 단서는 그때 남지 않는다.
  // (허용 목록은 defineProps 가 setup 밖으로 끌어올려져 지역 상수를 참조할 수 없어 인라인이다.)
  role: { type: String, required: true, validator: (value) => ['OWNER', 'WORKER'].includes(value) }
})

const router = useRouter()
const route = useRoute()
const isOwner = computed(() => props.role === 'OWNER')
const logoSymbol = computed(() => (isOwner.value ? logoSymbolOwner : logoSymbolWorker))

// 사장 홈(지갑)은 전 지점 합산이라 지점 선택이 무의미하다 → select 대신 '전체지점' 고정 표시.
const isOwnerHome = computed(() => isOwner.value && route.path === '/owner/home')

const auth = useAuthStore()

const workplace = useWorkplaceStore()
const { activeWorkplaces, selectedId } = storeToRefs(workplace)

const notifications = useNotificationsStore()
const { unreadCount } = storeToRefs(notifications)

// select v-model — 선택 시 스토어에 반영
const branch = computed({
  get: () => selectedId.value,
  set: (id) => workplace.select(Number(id))
})

// 닫힌 select 는 폭이 좁아 긴 지점명을 말줄임한다. 잘린 이름을 마우스로도 확인할 수 있게
// 전체 이름을 title 로 준다(펼친 목록에는 원래 전체가 보인다).
const selectedName = computed(() => workplace.selected?.name ?? '')

onMounted(() => {
  if (isOwner.value) workplace.load()
  // 배지만 필요하다. 목록은 모달을 열 때 조회한다(SPEC-382-01 이 개수를 분리했다).
  notifications.loadUnreadCount()
  // 새 알림이 생기면 새로고침 없이 배지를 갱신한다(#386). 실패해도 위 조회는 그대로 동작한다.
  notifications.connect()
})

// 로그아웃·언마운트에서 끊지 않으면 서버 Emitter 가 타임아웃까지 남는다.
onUnmounted(() => {
  notifications.disconnect()
})

function goMyPage() {
  router.push(isOwner.value ? '/owner/mypage' : '/worker/mypage')
}

/**
 * 로고 → 홈. 목적지는 로그인한 사용자의 역할이 정한다.
 *
 * 역할→홈 매핑은 auth 스토어가 원본이고 라우터 가드 G2·G3 가 그것을 쓴다. 여기서 같은
 * 표를 다시 적으면 홈 경로가 바뀔 때 가드만 따라가고 상단바 로고는 조용히 어긋난다.
 * role prop 은 레이아웃이 박아 넣는 표시용 값이라 로고 색·지점 select 분기에만 쓴다.
 *
 * RouterLink 로 두는 이유는 하나다 — 실제 `<a href>` 라야 키보드 포커스·Enter 활성화·
 * "링크" 역할이 따라온다. click 핸들러를 단 span 으로는 이 셋을 각각 직접 만들어야 한다.
 * 중복 내비게이션은 근거가 아니다: vue-router 5 는 NAVIGATION_DUPLICATED 를 reject 가
 * 아니라 resolve 하므로 router.push 로도 경고나 unhandled rejection 이 나지 않는다.
 *
 * AppTopBar 는 탭 레이아웃에서만 렌더되므로 비로그인 화면에는 이 경로가 없다.
 */
const homePath = computed(() => auth.homeRoute())
</script>

<template>
  <header class="topbar">
    <!-- 로고는 홈으로 가는 관례적 경로다. 옆의 알림·마이페이지가 눌리는데 로고만 죽어 있으면
         눌러본 사용자에게 반응 없는 영역으로 남는다. -->
    <!-- 링크 이름은 aria-label 이 정한다. aria-label 이 있으면 접근 가능한 이름 계산이
         서브트리를 순회하지 않으므로(ANC 2C) svg 가 들고 있는 이름은 링크 이름에 섞이지
         않는다. svg 의 aria-hidden 은 그것과 다른 일을 한다 — 일부 스크린리더의 browse
         mode 가 링크 '안'의 role="img" 노드를 별도 항목으로 읽는 것을 막는다. -->
    <RouterLink :to="homePath" class="brand" aria-label="GigHub 홈">
      <img :src="logoSymbol" class="brand-logo" aria-hidden="true" />
      <span class="brand-name">GigHub</span>
    </RouterLink>

    <div class="right">
      <span v-if="isOwnerHome" class="branch branch--all">전체지점</span>
      <!--
        네이티브 화살표를 끄고(appearance:none) 우리 화살표를 얹는다. 네이티브 화살표는
        요소의 padding box 안에 UA 가 그려서 위치·크기를 제어할 수 없고, 긴 지점명이
        그 밑으로 파고든다. 직접 그려야 자리를 확실히 비울 수 있다.
      -->
      <span v-else-if="isOwner && activeWorkplaces.length" class="branch-picker">
        <select v-model="branch" class="branch" :title="selectedName" aria-label="지점 선택">
          <option v-for="w in activeWorkplaces" :key="w.workplaceId" :value="w.workplaceId">
            {{ w.name }}
          </option>
        </select>
        <ChevronDown :size="14" class="branch-caret" aria-hidden="true" />
      </span>

      <button type="button" class="icon-btn" aria-label="알림" @click="notifications.open()">
        <Bell :size="22" />
        <span v-if="unreadCount > 0" class="badge">{{ unreadCount > 9 ? '9+' : unreadCount }}</span>
      </button>

      <button type="button" class="icon-btn" aria-label="마이페이지" @click="goMyPage">
        <CircleUser :size="22" />
      </button>
    </div>
  </header>
</template>

<style scoped>
.topbar {
  position: sticky;
  top: 0;
  z-index: var(--z-tabbar);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
  padding: var(--space-md) var(--space-lg);
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
}
.brand {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  min-width: 0;
}
.brand-logo {
  width: 28px;
  height: 28px;
  /* logo-symbol PNG 는 정사각이 아니라(960x1102) 28x28 박스 안에서 잘리지 않게 비율을 지킨다. */
  object-fit: contain;
  flex-shrink: 0;
}
.brand-name {
  /* 상단바가 좁아지면 로고 이름이 먼저 줄어들게 둔다 — 지점명·아이콘이 밀리는 것보다 낫다. */
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: var(--text-lg);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.right {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  /* 기본값 min-width:auto 면 자식이 콘텐츠 폭 아래로 못 줄어 지점명이 길 때 넘친다. */
  min-width: 0;
}
.branch {
  max-width: 132px;
  padding: 4px var(--space-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  color: var(--color-text);
  background: var(--color-surface);
}
/* 사장 홈 전용 — 선택 불가한 '전체지점' 라벨 */
.branch--all {
  display: inline-flex;
  align-items: center;
  color: var(--color-text-sub);
}

/* ---- 지점 select (긴 지점명 처리) ----
   닫힌 상태는 말줄임, 펼친 상태는 네이티브 팝업이 내용 폭에 맞춰 열려 전체 이름이 보인다.
   그래서 잘라도 정보가 사라지지 않는다. select 는 줄바꿈이 불가능하고, 높이가 늘면
   OwnerAttendanceView 의 sticky 헤더(top:53px)가 어긋나므로 말줄임이 유일하게 맞는 선택이다. */
.branch-picker {
  position: relative;
  display: inline-flex;
  align-items: center;
  min-width: 0; /* 좁은 화면에서 아이콘을 밀어내지 않고 select 가 먼저 줄어든다 */
}
select.branch {
  width: 100%;
  /* caret(14px) + 좌우 여백. 네이티브 화살표를 끈 자리를 이 여백이 대신한다. */
  padding-right: 26px;
  appearance: none;
  -webkit-appearance: none;
  -moz-appearance: none;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.branch-caret {
  position: absolute;
  right: var(--space-sm);
  color: var(--color-text-sub);
  /* 화살표를 눌러도 select 가 열려야 한다 — 클릭을 가로채지 않는다. */
  pointer-events: none;
}

.icon-btn {
  position: relative;
  /* 지점명이 길어도 알림·마이페이지는 줄어들거나 잘리지 않는다. */
  flex-shrink: 0;
  color: var(--color-text);
}
.badge {
  position: absolute;
  top: -4px;
  right: -4px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: var(--radius-pill);
  background: var(--color-danger);
  color: var(--color-on-primary);
  font-size: 10px;
  line-height: 16px;
  text-align: center;
}
</style>
