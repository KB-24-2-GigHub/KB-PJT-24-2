<script setup>
/**
 * [F] 알바생 근무 정보 상세  ·  /worker/work/work-cases/:workCaseId  ·  WORKER(본인 근무)
 * 근무 정보 확인(제목·날짜·시간·휴게·일급·정산 상태).
 * 연계 API: GET /work-cases/{id} · GET /work-cases/{id}/workplace-contact  →  @/services/workCases
 * route.params.workCaseId 사용. 공통: StatusChip · 문의하기 시트.
 * 임금분쟁 DEMO 화면에서 신고와 저장된 검토 상태를 확인한다.
 */
import { FileText, Phone } from 'lucide-vue-next'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import AppBackHeader from '@/components/common/AppBackHeader.vue'
import BaseBottomSheet from '@/components/common/BaseBottomSheet.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import StatusChip from '@/components/common/StatusChip.vue'
import SettlementBreakdown from '@/components/settlement/SettlementBreakdown.vue'
import { contractFileUrl } from '@/services/documents'
import { getOwnerContact, getWorkCase } from '@/services/workCases'
import { useUiStore } from '@/stores/ui'
import {
  formatDate,
  formatDuration,
  formatKRW,
  formatPhoneInput,
  formatSeoulDateTime,
  formatSeoulTimeRange,
  onlyDigits
} from '@/utils/format'

const route = useRoute()
const router = useRouter()
const ui = useUiStore()

const workCaseId = computed(() => route.params.workCaseId)
const workCase = ref(null)
const loading = ref(true)
const contractViewUrl = computed(() => {
  const documentId = workCase.value?.contract?.documentId
  return documentId ? contractFileUrl(documentId, 'view') : ''
})

// Settlement(정산)과 Escrow(예치)는 서로 다른 상태 축이라 칩은 각각 그대로 노출하고,
// 이 문구는 두 축을 조합했을 때만 뜻이 분명해지는 경우(NO_SHOW 환불 vs WORKER 지급,
// CHECK_OUT_MISSING 결정 전 등)만 보충 설명한다. 서버가 보내지 않은 시각·금액은 만들지 않는다.
const settlementMessage = computed(() => {
  const wc = workCase.value
  const settlement = wc?.settlement
  if (!settlement) return null

  // ON_HOLD는 근무 상태와 무관하게 서버 값 그대로 보류로 표시한다(NO_SHOW·CHECK_OUT_MISSING도 포함).
  if (settlement.status === 'ON_HOLD') {
    return '정산이 보류됐어요'
  }
  if (wc.status === 'NO_SHOW') {
    return settlement.status === 'REFUNDED'
      ? '사장님 환불 완료 · 회원님 지급 내역은 없어요'
      : '사장님 환불 승인 대기 중 · 회원님 획득 금액은 0원이에요'
  }
  if (wc.status === 'CHECK_OUT_MISSING') {
    return settlement.status === 'REFUNDED'
      ? '사장님 환불 완료 · 회원님 지급 내역은 없어요'
      : '퇴근 누락 환불 승인 대기 중 · 회원님 획득 금액은 0원이에요'
  }

  switch (settlement.status) {
    case 'SCHEDULED':
      return settlement.dueAt ? `${formatSeoulDateTime(settlement.dueAt)} 지급 예정` : '지급 예정'
    case 'PROCESSING':
      return '정산 처리 중이에요'
    case 'COMPLETED':
      if (!Number.isSafeInteger(settlement.workerPaidAmount)) return '지급 완료 · 금액 확인 필요'
      return settlement.completedAt
        ? `${formatSeoulDateTime(settlement.completedAt)} · ${formatKRW(settlement.workerPaidAmount)} 지급 완료`
        : `${formatKRW(settlement.workerPaidAmount)} 지급 완료`
    case 'FAILED':
      return '정산이 실패했어요'
    default:
      return null
  }
})

// workCaseId가 빠르게 바뀌면(뒤로가기 후 다른 근무 진입 등) 먼저 보낸 요청이 나중에
// 도착해 최신 화면을 덮어쓸 수 있다. 시퀀스 번호로 최신 요청의 응답만 반영한다.
let loadSeq = 0
async function load(id) {
  const seq = ++loadSeq
  workCase.value = null
  loading.value = true
  try {
    const data = await getWorkCase(id)
    if (seq !== loadSeq) return
    workCase.value = data
  } catch {
    if (seq !== loadSeq) return
    ui.toast('근무 정보를 불러오지 못했습니다.', { type: 'danger' })
  } finally {
    if (seq === loadSeq) loading.value = false
  }
}

watch(workCaseId, load, { immediate: true })

/* ---- 문의하기 시트 ---- */
const contactOpen = ref(false)
const contact = ref(null)
const contactLoading = ref(false)
const contactError = ref(null)

async function openContact() {
  contact.value = null
  contactError.value = null
  contactOpen.value = true
  contactLoading.value = true
  try {
    contact.value = await getOwnerContact(workCaseId.value)
  } catch (error) {
    contactError.value = error
    const unavailable = error?.code === 'FEATURE_UNAVAILABLE'
    ui.toast(
      unavailable ? '사장님 연락처는 현재 준비 중인 기능입니다.' : '연락처를 불러오지 못했습니다.',
      { type: unavailable ? 'info' : 'warning' }
    )
  } finally {
    contactLoading.value = false
  }
}

function openDispute() {
  router.push(`/worker/work/work-cases/${workCaseId.value}/report`)
}

function goHome() {
  router.push('/worker/home')
}
</script>

<template>
  <div class="sub-page">
    <AppBackHeader title="근무 정보" />
    <main class="screen-body">
      <p v-if="loading" class="loading">불러오는 중…</p>

      <EmptyState v-else-if="!workCase" message="근무 정보를 찾을 수 없습니다." />

      <template v-else>
        <section class="detail-card">
          <header class="head">
            <div>
              <p class="workplace">{{ workCase.workplaceName }}</p>
              <h1 class="title">{{ workCase.title }}</h1>
            </div>
            <div class="chips">
              <StatusChip :status="workCase.status" kind="workCase" />
              <StatusChip
                v-if="workCase.settlement"
                :status="workCase.settlement.status"
                kind="settle"
              />
              <StatusChip v-if="workCase.escrow" :status="workCase.escrow.status" kind="escrow" />
            </div>
          </header>

          <p v-if="settlementMessage" class="settlement-message">{{ settlementMessage }}</p>

          <dl class="info">
            <div class="detail-row">
              <dt>근무일</dt>
              <dd>{{ formatDate(workCase.workDate) }}</dd>
            </div>
            <div class="detail-row">
              <dt>근무 시간</dt>
              <dd>{{ formatSeoulTimeRange(workCase.startsAt, workCase.endsAt) }}</dd>
            </div>
            <div class="detail-row">
              <dt>휴게 시간</dt>
              <dd>
                {{ formatDuration(workCase.breakMinutes) }}
                <span class="break-tag">{{ workCase.breakPaid ? '유급' : '무급' }}</span>
              </dd>
            </div>
            <div class="detail-row">
              <dt>일급</dt>
              <dd class="wage">{{ formatKRW(workCase.dailyWage) }}</dd>
            </div>
          </dl>
        </section>

        <SettlementBreakdown
          v-if="workCase.settlement"
          :settlement="workCase.settlement"
        />

        <div class="actions">
          <a
            v-if="contractViewUrl"
            :href="contractViewUrl"
            target="_blank"
            rel="noopener"
            class="contract-link"
          >
            <FileText :size="18" />
            최종 근로계약서 보기
          </a>
          <BaseButton variant="secondary" size="lg" block @click="openContact">
            <Phone :size="18" />
            사장님께 문의
          </BaseButton>
          <BaseButton variant="secondary" size="lg" block @click="openDispute">
            임금분쟁 신고·조회
          </BaseButton>
          <BaseButton variant="worker" size="lg" block @click="goHome"> 홈으로 </BaseButton>
        </div>
      </template>
    </main>

    <BaseBottomSheet :open="contactOpen" title="사장님 연락처" @close="contactOpen = false">
      <p v-if="contactLoading" class="contact-loading">연락처 불러오는 중…</p>
      <!-- 서버는 구분 문자 없는 숫자를 주므로 tel: 은 그대로 쓰고 화면 표기만 포맷한다. -->
      <a v-else-if="contact" class="contact-row" :href="`tel:${onlyDigits(contact.phone)}`">
        <Phone :size="18" />
        <span>{{ contact.ownerName }}</span>
        <strong>{{ formatPhoneInput(contact.phone) }}</strong>
      </a>
      <p v-else class="contact-loading">
        {{
          contactError?.code === 'FEATURE_UNAVAILABLE'
            ? '사장님 연락처는 현재 준비 중인 기능입니다.'
            : '연락처를 불러오지 못했습니다.'
        }}
      </p>
    </BaseBottomSheet>
  </div>
</template>

<style scoped>
.screen-body {
  padding: var(--space-lg);
}
.loading {
  margin-top: var(--space-xl);
  text-align: center;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.detail-card {
  padding: var(--space-lg);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-md);
}
.workplace {
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.title {
  margin-top: var(--space-xs);
  font-size: var(--text-xl);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.chips {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: var(--space-xs);
  flex-shrink: 0;
}
.settlement-message {
  margin-top: var(--space-sm);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.info {
  margin-top: var(--space-lg);
  padding-top: var(--space-md);
  border-top: 1px solid var(--color-border);
}
.detail-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: var(--space-sm) 0;
}
.detail-row dt {
  font-size: var(--text-md);
  color: var(--color-text-sub);
}
.detail-row dd {
  font-size: var(--text-md);
  color: var(--color-text);
}
.break-tag {
  margin-left: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.wage {
  font-weight: var(--weight-bold);
}
.actions {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  margin-top: var(--space-lg);
}
.contract-link {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  width: 100%;
  padding: var(--space-md) var(--space-lg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  color: var(--color-worker);
  background: var(--color-surface);
  font-size: var(--text-lg);
  font-weight: var(--weight-medium);
}
.contact-loading {
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.contact-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  color: var(--color-text);
}
.contact-row strong {
  margin-left: auto;
  font-weight: var(--weight-bold);
  color: var(--color-worker);
}
</style>
