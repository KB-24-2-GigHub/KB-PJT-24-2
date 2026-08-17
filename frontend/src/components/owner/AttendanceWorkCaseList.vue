<script setup>
/**
 * 근무 카드 목록 — 목록 뷰와 캘린더 뷰(선택한 날짜)가 **같은 마크업을 공유**한다.
 *
 * 이 컴포넌트를 따로 뺀 이유: 두 뷰에서 항목 모양과 이동 경로
 * (/owner/attendance/work-cases/:workCaseId)가 어긋나지 않게 하기 위해서다.
 * 실제 라우팅·링크 복사는 부모가 처리하고, 여기서는 클릭을 이벤트로 올려보내기만 한다.
 *
 * 사용:
 *   <AttendanceWorkCaseList
 *     :work-cases="items" :copying-id="copyingId"
 *     @select="goDetail" @copy-invite="onCopyInvite" />
 */
import { Link2 } from 'lucide-vue-next'

import StatusChip from '@/components/common/StatusChip.vue'
import { canIssueInvitation } from '@/constants/workCaseStatus'
import { formatDate, formatSeoulTimeRange } from '@/utils/format'

defineProps({
  /** 표시할 근무 목록(서버 조회 결과 그대로) */
  workCases: { type: Array, default: () => [] },
  /** 연결 링크 생성 중인 근무 id — 중복 클릭 방지용 */
  copyingId: { type: [Number, String], default: null },
  /** 날짜를 항목에 표시할지. 캘린더의 '선택한 날짜' 목록은 날짜가 이미 위에 있어 끈다. */
  showDate: { type: Boolean, default: true }
})

const emit = defineEmits(['select', 'copy-invite'])
</script>

<template>
  <ul class="list">
    <li v-for="workCase in workCases" :key="workCase.workCaseId" class="item">
      <button type="button" class="item-btn" @click="emit('select', workCase.workCaseId)">
        <div class="item-head">
          <span class="item-title">{{ workCase.title }}</span>
          <StatusChip :status="workCase.status" kind="workCase" />
        </div>
        <p class="item-when">
          <template v-if="showDate">{{ formatDate(workCase.workDate) }} · </template>
          {{ formatSeoulTimeRange(workCase.startsAt, workCase.endsAt) }}
        </p>
        <p class="item-worker">
          {{ workCase.worker?.name ?? '아직 매칭된 알바생이 없어요' }}
        </p>
      </button>

      <!-- 서버의 발급 허용 조건(DRAFT · 미매칭 · 시작 전)을 그대로 재현한다.
           목록 Item 에 capability 필드가 없어 세 조건을 직접 본다(@/constants/workCaseStatus). -->
      <button
        v-if="canIssueInvitation(workCase)"
        type="button"
        class="copy-btn"
        :disabled="copyingId === workCase.workCaseId"
        @click="emit('copy-invite', workCase.workCaseId)"
      >
        <Link2 :size="14" />
        {{ copyingId === workCase.workCaseId ? '링크 만드는 중…' : '연결 링크 발급·복사' }}
      </button>
    </li>
  </ul>
</template>

<style scoped>
.list {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  margin-top: var(--space-sm);
  /* UA 기본 ul padding-left 무효화. base.css 가 이미 전역으로 지우지만,
     이 목록은 scoped 규칙이 padding 을 직접 잡으므로 여기서도 명시한다. */
  padding: 0;
}
.item {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
}
.item-btn {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  width: 100%;
  padding: var(--space-md) var(--space-lg);
  text-align: left;
}
/* 카드 하단 액션 — 수락 전 근무의 연결 링크 발급·복사 */
.copy-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  width: 100%;
  padding: var(--space-sm);
  border-top: 1px solid var(--color-border);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-owner);
}
.copy-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
.item-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
}
.item-title {
  font-size: var(--text-md);
  font-weight: var(--weight-medium);
  color: var(--color-text);
}
.item-when,
.item-worker {
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
</style>
