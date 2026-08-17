<script setup>
/**
 * 알림 모달 — 공통. App.vue 에 한 번 배치하며, AppTopBar 종 아이콘이 연다.
 * 상태·데이터는 notifications 스토어. 항목 클릭 시 읽음 처리.
 */
import { Bell } from 'lucide-vue-next'
import { storeToRefs } from 'pinia'

import BaseBottomSheet from '@/components/common/BaseBottomSheet.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { useNotificationsStore } from '@/stores/notifications'
import { formatDateTime } from '@/utils/format'

const store = useNotificationsStore()
const { items, isOpen, loading, loadError } = storeToRefs(store)
</script>

<template>
  <BaseBottomSheet :open="isOpen" title="알림" @close="store.close()">
    <p v-if="loading" class="state">불러오는 중…</p>
    <EmptyState v-else-if="loadError" message="알림을 불러오지 못했습니다.">
      <template #icon><Bell :size="32" /></template>
      잠시 후 다시 열어봐 주세요.
    </EmptyState>
    <EmptyState v-else-if="items.length === 0" message="새 알림이 없습니다." />
    <ul v-else class="list">
      <li
        v-for="n in items"
        :key="n.notificationId"
        class="item"
        :class="{ unread: !n.isRead }"
        @click="store.markRead(n.notificationId)"
      >
        <div class="item-head">
          <strong class="title">{{ n.title }}</strong>
          <span class="date">{{ formatDateTime(n.createdAt) }}</span>
        </div>
        <p class="content">{{ n.content }}</p>
      </li>
    </ul>
  </BaseBottomSheet>
</template>

<style scoped>
.state {
  padding: var(--space-xl) 0;
  text-align: center;
  color: var(--color-text-sub);
}
.list {
  display: flex;
  flex-direction: column;
}
.item {
  padding: var(--space-md) 0;
  border-bottom: 1px solid var(--color-border);
  cursor: pointer;
}
.item.unread .title::before {
  content: '';
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: var(--space-xs);
  border-radius: var(--radius-pill);
  background: var(--color-danger);
  vertical-align: middle;
}
.item-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-sm);
}
.title {
  font-size: var(--text-md);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.date {
  flex-shrink: 0;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.content {
  margin-top: 2px;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
</style>
