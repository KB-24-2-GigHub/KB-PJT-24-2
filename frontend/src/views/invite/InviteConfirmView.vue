<script setup>
/**
 * WORKER 초대 조건 확인과 Body 없는 최종 동의 화면.
 *
 * Token은 현재 경로에서만 사용하고 저장·로그하지 않는다. 한 번의 수락 의도에는 화면 메모리의
 * 같은 멱등 Key를 사용하며, 결과가 불확실한 재확인에도 Key를 유지한다.
 */
import { CheckCircle2, FileText, RefreshCw } from 'lucide-vue-next'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import AppBackHeader from '@/components/common/AppBackHeader.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import PdfCanvasViewer from '@/components/common/PdfCanvasViewer.vue'
import TrustBadge from '@/components/common/TrustBadge.vue'
import { useDocumentPreview } from '@/composables/useDocumentPreview'
import { contractFileUrl } from '@/services/documents'
import { newIdempotencyKey } from '@/services/http'
import { confirmInvite, getInvite } from '@/services/invites'
import { getWorkCase } from '@/services/workCases'
import { useWalletStore } from '@/stores/wallet'
import { useUiStore } from '@/stores/ui'
import {
  formatDuration,
  formatKRW,
  formatSeoulDateTime,
  formatSeoulTimeRange
} from '@/utils/format'
import { BADGE_TYPE } from '@/utils/constants'
import {
  invitationErrorMessage,
  isInvitationForbidden,
  shouldRetainAcceptanceKey
} from '@/utils/invitation'

const route = useRoute()
const router = useRouter()
const ui = useUiStore()
const walletStore = useWalletStore()

const token = computed(() => String(route.params.token ?? ''))
const invite = ref(null)
// 마이페이지("안심사장 Lv.N")와 같은 문구 관례. BADGE_TYPE 은 이미 있는 상수라 새로 만들지 않는다.
const ownerBadgeTitle = computed(() => BADGE_TYPE[invite.value?.ownerBadge?.badgeType]?.title ?? '')
const loading = ref(true)
const errorMsg = ref('')
const agreed = ref(false)
const confirming = ref(false)
const retryAvailable = ref(false)
const accepted = ref(null)
const acceptedWorkCase = ref(null)
const synchronizing = ref(false)
const syncError = ref(false)

// 비밀값을 반응형 Store나 Web Storage에 넣지 않는다. 이 화면 인스턴스가 사라지면 함께 폐기된다.
let acceptanceKey = null
let inviteLoadSequence = 0

const workDateText = computed(() => formatSeoulDateTime(invite.value?.startsAt).split(' ')[0])
const contractDocumentId = computed(() => acceptedWorkCase.value?.contract?.documentId ?? null)
const {
  previewBlob: contractBlob,
  loadPreview: loadContractPreview,
  clearPreview: clearContractPreview
} = useDocumentPreview()
const contractDownloadUrl = computed(() =>
  contractDocumentId.value ? contractFileUrl(contractDocumentId.value, 'download') : ''
)

onMounted(loadInvite)

watch(token, () => {
  // 같은 컴포넌트에서 다른 초대 경로로 이동하면 이전 Token의 의도와 응답을 완전히 버린다.
  acceptanceKey = null
  invite.value = null
  agreed.value = false
  confirming.value = false
  retryAvailable.value = false
  accepted.value = null
  acceptedWorkCase.value = null
  syncError.value = false
  clearContractPreview()
  void loadInvite()
})

async function loadInvite() {
  const requestSequence = ++inviteLoadSequence
  const requestToken = token.value
  loading.value = true
  errorMsg.value = ''
  try {
    const result = await getInvite(requestToken)
    if (requestSequence !== inviteLoadSequence) return
    invite.value = result
  } catch (error) {
    if (requestSequence !== inviteLoadSequence) return
    if (isInvitationForbidden(error)) {
      await router.replace('/forbidden')
      return
    }
    errorMsg.value = invitationErrorMessage(error)
  } finally {
    if (requestSequence === inviteLoadSequence) loading.value = false
  }
}

/** 수락 성공 뒤 권위 있는 근무·지갑·계약 연결을 다시 읽는다. */
async function synchronizeAcceptedState() {
  synchronizing.value = true
  syncError.value = false

  const [workCaseResult] = await Promise.allSettled([
    getWorkCase(accepted.value.workCaseId),
    walletStore.loadHome()
  ])

  if (workCaseResult.status === 'fulfilled') {
    acceptedWorkCase.value = workCaseResult.value
  }

  let contractPreviewFailed = false
  const documentId = acceptedWorkCase.value?.contract?.documentId
  if (documentId) {
    try {
      await loadContractPreview(documentId)
    } catch {
      contractPreviewFailed = true
    }
  } else {
    clearContractPreview()
  }

  // 수락 Aggregate는 계약 문서까지 원자 생성한다. 식별자가 없으면 성공을 되돌리지 않고
  // 동기화 실패로만 표시해 사용자가 같은 수락을 새 Key로 다시 보내지 않게 한다.
  syncError.value =
    workCaseResult.status === 'rejected' ||
    !acceptedWorkCase.value?.contract?.documentId ||
    Boolean(walletStore.error) ||
    contractPreviewFailed
  synchronizing.value = false
}

async function confirm() {
  if (confirming.value || accepted.value) return
  if (!agreed.value) {
    ui.toast('근무 조건과 근로계약서 자동 생성에 동의해주세요.', { type: 'warning' })
    return
  }

  if (!acceptanceKey) acceptanceKey = newIdempotencyKey()
  const requestToken = token.value
  confirming.value = true
  try {
    const result = await confirmInvite(requestToken, { idempotencyKey: acceptanceKey })
    if (requestToken !== token.value) return
    accepted.value = result
    acceptanceKey = null
    retryAvailable.value = false
    ui.toast('근무가 확정됐어요.', { type: 'success' })
    await synchronizeAcceptedState()
  } catch (error) {
    if (requestToken !== token.value) return
    const requestWasUncertain = retryAvailable.value
    retryAvailable.value = shouldRetainAcceptanceKey(error, { requestWasUncertain })
    if (!retryAvailable.value) acceptanceKey = null

    if (isInvitationForbidden(error)) {
      await router.replace('/forbidden')
      return
    }
    ui.toast(invitationErrorMessage(error, { sameIntentRetry: retryAvailable.value }), {
      type: retryAvailable.value ? 'warning' : 'danger',
      duration: 6000
    })
  } finally {
    confirming.value = false
  }
}

function goWorkCase() {
  router.push(`/worker/work/work-cases/${accepted.value.workCaseId}`)
}

/**
 * 확정 직후의 주 경로는 안심지갑(홈)이다.
 *
 * 서버가 수락 Aggregate 에서 일급을 에스크로로 잡으므로(escrowStatus=HELD), 알바생이
 * 이 시점에 가장 확인하고 싶은 것은 "내 임금이 안전하게 예치됐다"는 사실이다.
 * synchronizeAcceptedState 가 이미 지갑을 다시 읽어 둬서 홈은 최신 값을 보여준다.
 */
function goHome() {
  router.push('/worker/home')
}
</script>

<template>
  <div class="sub-page">
    <AppBackHeader :title="accepted ? '근무 확정 완료' : '근무 확정'" to="/worker/home" />
    <main class="screen-body">
      <p v-if="loading" class="loading">불러오는 중…</p>

      <EmptyState v-else-if="errorMsg" :message="errorMsg" />

      <template v-else-if="accepted">
        <section class="success-card">
          <CheckCircle2 :size="48" aria-hidden="true" />
          <h1>근무가 확정됐어요</h1>
          <p>근무와 임금 예치 상태를 서버 기준으로 다시 확인했습니다.</p>
          <strong>{{ accepted.escrowStatus === 'HELD' ? '임금 예치 완료' : '확정 완료' }}</strong>
        </section>

        <p v-if="synchronizing" class="loading">근무·지갑·계약을 최신화하는 중…</p>

        <section v-else-if="acceptedWorkCase" class="accepted-detail">
          <h2>{{ acceptedWorkCase.title }}</h2>
          <p>{{ acceptedWorkCase.workplaceName }} · {{ formatKRW(acceptedWorkCase.dailyWage) }}</p>
          <p>{{ formatSeoulTimeRange(acceptedWorkCase.startsAt, acceptedWorkCase.endsAt) }}</p>
        </section>

        <section v-if="contractDocumentId" class="contract-section">
          <div class="section-head">
            <h2>최종 근로계약서</h2>
            <span>서명 완료본</span>
          </div>
          <PdfCanvasViewer v-if="contractBlob" :blob="contractBlob" class="contract-preview" />
          <p v-else class="contract-preview-error">
            미리보기를 불러오지 못했어요. 아래에서 계약서를 다운로드할 수 있어요.
          </p>
          <a :href="contractDownloadUrl" class="download-link">
            <FileText :size="18" />
            계약서 다운로드
          </a>
        </section>

        <div v-if="syncError" class="sync-warning">
          <p>근무는 확정됐지만 최신 정보를 모두 불러오지 못했어요.</p>
          <BaseButton
            variant="secondary"
            block
            :disabled="synchronizing"
            @click="synchronizeAcceptedState"
          >
            <RefreshCw :size="17" />
            다시 불러오기
          </BaseButton>
        </div>

        <!--
          동기화 실패(syncError)일 때도 두 경로를 막지 않는다. 근무는 이미 확정됐으므로
          화면에 가두면 사용자가 같은 수락을 다시 시도하게 된다.
        -->
        <div class="accepted-actions">
          <BaseButton variant="worker" size="lg" block @click="goHome"> 홈으로 </BaseButton>
          <BaseButton variant="secondary" block @click="goWorkCase">근무 상세 확인</BaseButton>
        </div>
      </template>

      <template v-else-if="invite">
        <section class="invite-head">
          <div class="head-info">
            <p class="workplace">{{ invite.workplaceName }}</p>
            <h1 class="title">{{ invite.title }}</h1>
          </div>
          <!--
            SPEC-484-01: ownerBadge는 0단계도 null로 감추지 않고 항상 채워진 객체로 온다
            (worker.badge, #472와 같은 관례) — 아래 v-else는 예상 밖 응답을 받았을 때만
            타는 방어적 폴백이다.
          -->
          <div v-if="invite.ownerBadge" class="owner-badge">
            <TrustBadge role="owner" :level="invite.ownerBadge.level" :size="36" />
            <span class="badge-label">{{ ownerBadgeTitle }} Lv.{{ invite.ownerBadge.level }}</span>
          </div>
          <span v-else class="badge-empty">등록된 배지 없음</span>
        </section>

        <section class="detail-card">
          <dl class="info">
            <div class="detail-row">
              <dt>근무일</dt>
              <dd>{{ workDateText }}</dd>
            </div>
            <div class="detail-row">
              <dt>근무 시간</dt>
              <dd>{{ formatSeoulTimeRange(invite.startsAt, invite.endsAt) }}</dd>
            </div>
            <div class="detail-row">
              <dt>휴게 시간</dt>
              <dd>
                {{ formatDuration(invite.breakMinutes) }}
                <span class="break-tag">{{ invite.breakPaid ? '유급' : '무급' }}</span>
              </dd>
            </div>
            <div class="detail-row">
              <dt>일급</dt>
              <dd class="wage">{{ formatKRW(invite.dailyWage) }}</dd>
            </div>
            <!--
              termsVersion 은 화면에 내보내지 않는다. 서버가 조건 변경을 감지하는 내부
              장치일 뿐이라 'v3' 가 알바생에게 뜻하는 바가 없고, 수락 직전 화면에서는
              오히려 무엇을 놓쳤나 하는 오해를 준다. 서버 응답은 그대로 둔다.
            -->
            <div class="detail-row">
              <dt>초대 만료</dt>
              <dd>{{ formatSeoulDateTime(invite.expiresAt) }}</dd>
            </div>
          </dl>
        </section>

        <label class="consent">
          <input v-model="agreed" type="checkbox" />
          <span>위 근무 조건과 서버가 최종 근로계약서를 자동 생성하는 것에 동의합니다.</span>
        </label>
        <p class="consent-note">
          확정 버튼을 누르면 현재 로그인한 알바생의 이름으로 최종 동의가 기록됩니다. 서명 이미지나
          별도 파일은 전송하지 않습니다.
        </p>

        <p class="warn">
          근무 확정 시점부터는 근무 조건 변경 및 취소가 불가합니다. 내용을 신중하게 확인해주세요.
        </p>

        <BaseButton variant="worker" size="lg" block :disabled="confirming" @click="confirm">
          {{
            confirming
              ? '확정 중…'
              : retryAvailable
                ? '같은 요청으로 다시 확인'
                : '동의하고 근무 확정'
          }}
        </BaseButton>
      </template>
    </main>
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

.invite-head {
  display: flex;
  align-items: center;
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
.badge-empty {
  flex-shrink: 0;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.owner-badge {
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  align-items: center;
  gap: var(--space-xs);
}
/* 마이페이지("{타이틀} Lv.N")와 같은 문구 관례. 아이콘만으로는 무슨 뱃지인지 안 읽힌다. */
.badge-label {
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-sub);
}

.detail-card,
.accepted-detail,
.contract-section {
  margin-top: var(--space-lg);
  padding: var(--space-lg);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.detail-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-md);
  padding: var(--space-sm) 0;
}
.detail-row dt,
.accepted-detail p {
  font-size: var(--text-md);
  color: var(--color-text-sub);
}
.detail-row dd {
  text-align: right;
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

.consent {
  display: flex;
  align-items: flex-start;
  gap: var(--space-sm);
  margin-top: var(--space-lg);
  padding: var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  color: var(--color-text);
  cursor: pointer;
}
.consent input {
  width: 18px;
  height: 18px;
  margin-top: 2px;
  accent-color: var(--color-worker);
}
.consent-note {
  margin-top: var(--space-sm);
  font-size: var(--text-sm);
  line-height: 1.6;
  color: var(--color-text-sub);
}
.warn {
  margin: var(--space-lg) 0;
  padding: var(--space-md);
  font-size: var(--text-sm);
  color: var(--color-danger);
  background: var(--color-danger-bg);
  border-radius: var(--radius-sm);
}

.success-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-xl) var(--space-lg);
  text-align: center;
  color: var(--color-worker);
  background: var(--color-worker-weak);
  border-radius: var(--radius-md);
}
.success-card h1,
.accepted-detail h2,
.section-head h2 {
  font-size: var(--text-lg);
  font-weight: var(--weight-bold);
  color: var(--color-text);
}
.success-card p {
  color: var(--color-text-sub);
}
.accepted-detail {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}
.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-md);
}
.section-head span {
  font-size: var(--text-sm);
  color: var(--color-worker);
}
/*
 * 수락 화면의 계약서는 확인용이라 화면을 다 차지하지 않는다. 높이를 제한하고 안에서
 * 스크롤한다. 문서 뷰어는 문서를 보는 것이 목적이라 이 제한을 두지 않는다.
 */
.contract-preview {
  max-height: 55vh;
  overflow-y: auto;
  border-radius: var(--radius-sm);
  background: var(--color-bg);
}
.contract-preview-error {
  padding: var(--space-lg);
  color: var(--color-text-sub);
  text-align: center;
  background: var(--color-bg);
  border-radius: var(--radius-sm);
}
.download-link {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  margin-top: var(--space-md);
  color: var(--color-worker);
  font-weight: var(--weight-medium);
}
.sync-warning {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  margin: var(--space-lg) 0;
  padding: var(--space-md);
  color: var(--color-text-sub);
  background: var(--color-bg);
  border-radius: var(--radius-sm);
}
.screen-body > .btn {
  margin-top: var(--space-lg);
}
/* 확정 완료 화면의 이동 버튼 묶음. 버튼이 .screen-body 의 직계가 아니게 되므로
   위 규칙이 걸리지 않아 여백을 여기서 준다. */
.accepted-actions {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  margin-top: var(--space-lg);
}
</style>
