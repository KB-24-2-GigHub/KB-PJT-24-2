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
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import LogoSymbol from '@/assets/images/logo/logo-symbol.svg'
import { useNotificationsStore } from '@/stores/notifications'
import { useWorkplaceStore } from '@/stores/workplace'

const props = defineProps({
  role: { type: String, required: true } // 'OWNER' | 'WORKER'
})

const router = useRouter()
const route = useRoute()
const isOwner = computed(() => props.role === 'OWNER')

// 사장 홈(지갑)은 전 지점 합산이라 지점 선택이 무의미하다 → select 대신 '전체지점' 고정 표시.
const isOwnerHome = computed(() => isOwner.value && route.path === '/owner/home')

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
})

function goMyPage() {
  router.push(isOwner.value ? '/owner/mypage' : '/worker/mypage')
}
</script>

<template>
  <header class="topbar">
    <span class="brand">
      <LogoSymbol
        class="brand-logo"
        :class="isOwner ? 'is-owner' : 'is-worker'"
        aria-label="Gig Hub"
      />
      <span class="brand-name">Gig Hub</span>
    </span>

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
  gap: var(--space-sm);
  min-width: 0;
}
.brand-logo {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
}
.brand-logo.is-owner {
  color: var(--color-owner);
}
.brand-logo.is-worker {
  color: var(--color-worker);
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
