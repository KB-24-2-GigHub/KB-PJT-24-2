<script setup>
/**
 * [G] 알바생 문서함  ·  /worker/documents  ·  WORKER  (탭 화면)
 * 근로계약서(시스템 생성 최종본, 읽기 전용) + 보건증(등록·발급일 수정·논리 삭제·공유·철회).
 * 연계 API: GET/POST/PATCH/DELETE /documents · GET/POST/DELETE /documents/{id}/shares
 *   · GET /worker/workplaces  →  @/services/documents · @/services/worker
 * 정책(#113·#183): 등록·수정·삭제·공유는 보건증만이다. 계약서에는 어떤 조작 버튼도 걸지 않는다
 *   — 서버가 409 CONTRACT_RETENTION_REQUIRED 로 거부한다. 삭제는 서버 capabilities.canDelete 로,
 *   공유·발급일 수정은 서버가 준 source·docType 으로 정한다(PATCH 에 대응하는 capability 는
 *   계약에 없다). 셋 다 서버 값이며 화면이 권한을 계산하지 않는다.
 * 공통: BaseBottomSheet(보건증 등록/공유) · BaseModal(발급일·삭제 확인) · 카드 클릭 → 뷰어
 */
import {
  FileText,
  Image as ImageIcon,
  MapPin,
  Pencil,
  Share2,
  Trash2,
  Upload
} from 'lucide-vue-next'
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import AppDateFieldCalendar from '@/components/common/AppDateFieldCalendar.vue'
import BaseBottomSheet from '@/components/common/BaseBottomSheet.vue'
import BaseButton from '@/components/common/BaseButton.vue'
import BaseModal from '@/components/common/BaseModal.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import {
  deleteDocument,
  listAllDocumentShares,
  listDocuments,
  revokeShare,
  shareDocument,
  updateDocumentIssuedDate,
  uploadDocument
} from '@/services/documents'
import { errorMessage, fieldErrorMap } from '@/services/http'
import { listAllWorkerWorkplaces } from '@/services/worker'
import { useUiStore } from '@/stores/ui'
import { docTypeLabel, isImageDocument } from '@/utils/document'
import { formatDate } from '@/utils/format'
import { hasNextPage } from '@/utils/page'

const router = useRouter()
const ui = useUiStore()

const ALLOWED_EXT = ['jpg', 'jpeg', 'png', 'pdf']

const docs = ref([])
const loading = ref(true)
const loadingMore = ref(false)
const loadError = ref(null)
const nextPage = ref(0)
const hasMore = ref(false)

const TABS = [
  { value: 'ALL', label: '전체' },
  { value: 'EMPLOYMENT_CONTRACT', label: '근로계약서' },
  { value: 'HEALTH_CERTIFICATE', label: '보건증' }
]
const activeTab = ref('ALL')

onMounted(load)
// 목록은 Page 단위(기본 20건)라 유형 필터를 화면에서 걸면 뒤 Page 문서가 사라진다.
watch(activeTab, load)

/** 소유 보건증만 공유 이력을 조회할 수 있다(다른 문서는 서버가 거부한다). */
function ownsHealthCertificate(doc) {
  return doc.docType === 'HEALTH_CERTIFICATE' && doc.source === 'OWN'
}

/**
 * 카드와 공유 시트가 쓰는 활성 공유 현황. 상태는 서버 계산값이다.
 *
 * 이력 전체를 모은 뒤 ACTIVE 만 남긴다. 이력은 REVOKED·EXPIRED 를 포함한 최신 생성순이라
 * 첫 Page 만 읽으면 오래된 ACTIVE 공유가 뒤 Page 로 밀려 사라지고, 그 사업장이 '공유할
 * 지점'에 다시 나타나 409 를 만들며 철회 경로까지 없어진다.
 */
async function loadActiveShares(doc) {
  try {
    const shares = await listAllDocumentShares(doc.documentId)
    doc.activeShares = shares.filter((share) => share.status === 'ACTIVE')
  } catch {
    doc.activeShares = []
  }
}

function query(page) {
  return { docType: activeTab.value === 'ALL' ? undefined : activeTab.value, page }
}

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const { content, page } = await listDocuments(query(0))
    const list = content ?? []
    await Promise.all(list.filter(ownsHealthCertificate).map(loadActiveShares))
    docs.value = list
    nextPage.value = 1
    hasMore.value = hasNextPage(page)
  } catch (error) {
    loadError.value = error
    docs.value = []
    hasMore.value = false
    ui.toast('문서를 불러오지 못했어요.', { type: 'danger' })
  } finally {
    loading.value = false
  }
}

/** 다음 Page 를 이어 붙인다. 없으면 남은 문서가 표시도 오류도 없이 사라진다. */
async function loadMore() {
  if (loadingMore.value || !hasMore.value) return
  loadingMore.value = true
  try {
    const { content, page } = await listDocuments(query(nextPage.value))
    const list = content ?? []
    await Promise.all(list.filter(ownsHealthCertificate).map(loadActiveShares))
    docs.value = [...docs.value, ...list]
    nextPage.value += 1
    hasMore.value = hasNextPage(page)
  } catch {
    ui.toast('문서를 더 불러오지 못했어요.', { type: 'danger' })
  } finally {
    loadingMore.value = false
  }
}

function goViewer(doc) {
  router.push(`/worker/documents/${doc.documentId}`)
}

/* ---- 보건증 등록(업로드) 시트 ---- */
const registerOpen = ref(false)
const file = ref(null)
const fileName = ref('')
const fileError = ref('')
const issuedDate = ref('')
const issuedError = ref('')
const submitting = ref(false)

function openRegister() {
  file.value = null
  fileName.value = ''
  fileError.value = ''
  issuedDate.value = ''
  issuedError.value = ''
  registerOpen.value = true
}

function onFile(e) {
  fileError.value = ''
  const picked = e.target.files?.[0]
  if (!picked) {
    file.value = null
    fileName.value = ''
    return
  }
  const ext = picked.name.split('.').pop()?.toLowerCase() ?? ''
  if (!ALLOWED_EXT.includes(ext)) {
    fileError.value = 'jpg · png · pdf 파일만 등록할 수 있어요.'
    file.value = null
    fileName.value = ''
    e.target.value = ''
    return
  }
  file.value = picked
  fileName.value = picked.name
}

/** 서버 fieldErrors 를 입력 칸에 그대로 붙인다. 만료일은 서버가 계산하므로 보내지 않는다. */
function applyUploadFieldErrors(error) {
  const fields = fieldErrorMap(error)
  fileError.value = fields.file ?? ''
  issuedError.value = fields.issuedDate ?? ''
  return Boolean(fields.file || fields.issuedDate)
}

async function submitUpload() {
  fileError.value = ''
  issuedError.value = ''
  if (!file.value) {
    fileError.value = '보건증 파일을 선택해 주세요.'
    return
  }
  if (!issuedDate.value) {
    issuedError.value = '발급일을 입력해 주세요.'
    return
  }
  submitting.value = true
  try {
    const formData = new FormData()
    formData.append('docType', 'HEALTH_CERTIFICATE')
    formData.append('file', file.value)
    formData.append('issuedDate', issuedDate.value)
    await uploadDocument(formData)
    ui.toast('보건증을 등록했어요.', { type: 'success' })
    registerOpen.value = false
    await load()
  } catch (error) {
    if (!applyUploadFieldErrors(error)) {
      ui.toast(errorMessage(error, '보건증 등록에 실패했어요.'), { type: 'danger' })
    }
  } finally {
    submitting.value = false
  }
}

/* ---- 발급일 수정 모달 ---- */
const editDoc = ref(null)
const editDate = ref('')
const editOpen = ref(false)
const editError = ref('')
const editSaving = ref(false)

function openEdit(doc) {
  editDoc.value = doc
  editDate.value = doc.issuedDate ?? ''
  editError.value = ''
  editOpen.value = true
}

async function saveEdit() {
  if (!editDate.value) {
    editError.value = '발급일을 입력해 주세요.'
    return
  }
  editSaving.value = true
  editError.value = ''
  try {
    await updateDocumentIssuedDate(editDoc.value.documentId, { issuedDate: editDate.value })
    ui.toast('발급일을 수정했어요.', { type: 'success' })
    editOpen.value = false
    await load()
  } catch (error) {
    const fields = fieldErrorMap(error)
    if (fields.issuedDate) {
      editError.value = fields.issuedDate
    } else {
      ui.toast(errorMessage(error, '발급일 수정에 실패했어요.'), { type: 'danger' })
    }
  } finally {
    editSaving.value = false
  }
}

/* ---- 보건증 삭제 확인 모달 ---- */
const deleteDoc = ref(null)
const deleteOpen = ref(false)
const deleting = ref(false)

function openDelete(doc) {
  deleteDoc.value = doc
  deleteOpen.value = true
}

async function confirmDelete() {
  deleting.value = true
  try {
    await deleteDocument(deleteDoc.value.documentId)
    ui.toast('보건증을 삭제했어요. 공유도 함께 해제됐어요.', { type: 'success' })
    deleteOpen.value = false
    await load()
  } catch (error) {
    // 계약서 삭제 요청이 남는 방어 경로 — 서버는 보존 정책을 409 로 알린다.
    if (error?.code === 'CONTRACT_RETENTION_REQUIRED') {
      ui.toast('근로계약서는 보존 정책에 따라 삭제할 수 없어요.', { type: 'warning' })
    } else {
      ui.toast(errorMessage(error, '삭제에 실패했어요.'), { type: 'danger' })
    }
  } finally {
    deleting.value = false
  }
}

/* ---- 보건증 공유 관리 시트 ---- */
const shareDoc = ref(null)
const shareOpen = ref(false)
const shareTargets = ref([])
const shareLoading = ref(false)
const shareBusy = ref(false)

const activeShares = computed(() => shareDoc.value?.activeShares ?? [])
const sharedIds = computed(() => new Set(activeShares.value.map((s) => s.workplaceId)))
// 서버가 최종 판정하지만, 이미 공유한 지점을 다시 눌러 409 를 만나게 하지는 않는다.
const availableTargets = computed(() =>
  shareTargets.value.filter((w) => !sharedIds.value.has(w.workplaceId))
)

async function openShare(doc) {
  shareDoc.value = doc
  shareOpen.value = true
  shareLoading.value = true
  try {
    // 후보를 한 Page 만 읽으면 남은 사업장이 표시도 오류도 없이 사라진다.
    const [workplaces] = await Promise.all([listAllWorkerWorkplaces(), loadActiveShares(doc)])
    shareTargets.value = workplaces
  } catch (error) {
    shareTargets.value = []
    ui.toast(errorMessage(error, '공유 정보를 불러오지 못했어요.'), { type: 'warning' })
  } finally {
    shareLoading.value = false
  }
}

async function doShare(workplace) {
  shareBusy.value = true
  try {
    await shareDocument(shareDoc.value.documentId, { workplaceId: workplace.workplaceId })
    ui.toast(`${workplace.workplaceName}에 공유했어요.`, { type: 'success' })
    await loadActiveShares(shareDoc.value)
  } catch (error) {
    // 만료 보건증·후보 없음(400)과 중복 공유·복수 근무 건(409)을 서버 문구 그대로 구분한다.
    ui.toast(errorMessage(error, '공유에 실패했어요.'), { type: 'danger' })
  } finally {
    shareBusy.value = false
  }
}

async function doRevoke(share) {
  shareBusy.value = true
  try {
    await revokeShare(shareDoc.value.documentId, share.workplaceId)
    ui.toast('공유를 취소했어요.', { type: 'success' })
    await loadActiveShares(shareDoc.value)
  } catch (error) {
    ui.toast(errorMessage(error, '공유 취소에 실패했어요.'), { type: 'danger' })
  } finally {
    shareBusy.value = false
  }
}
</script>

<template>
  <div class="worker-docs">
    <div class="toolbar">
      <div class="tabs" role="tablist" aria-label="문서 유형">
        <button
          v-for="tab in TABS"
          :key="tab.value"
          type="button"
          class="tab"
          :class="{ active: activeTab === tab.value }"
          @click="activeTab = tab.value"
        >
          {{ tab.label }}
        </button>
      </div>

      <button type="button" class="upload-btn" @click="openRegister">
        <Upload :size="16" /> 보건증 등록
      </button>
    </div>

    <p v-if="loading" class="loading">불러오는 중…</p>

    <template v-else>
      <EmptyState v-if="loadError" message="문서를 불러오지 못했어요." />

      <EmptyState v-else-if="docs.length === 0" message="표시할 문서가 없어요.">
        보건증을 등록하거나, 근무를 시작하면 근로계약서가 자동 저장돼요.
      </EmptyState>

      <ul v-else class="doc-list">
        <li
          v-for="doc in docs"
          :key="`${doc.documentId}-${doc.workCaseId ?? 'own'}`"
          class="doc-card"
        >
          <button type="button" class="doc-main" @click="goViewer(doc)">
            <span class="thumb">
              <ImageIcon v-if="isImageDocument(doc)" :size="20" />
              <FileText v-else :size="20" />
            </span>

            <span class="doc-info">
              <span class="doc-name">{{ doc.fileName }}</span>
              <span class="doc-meta">
                {{ formatDate(doc.issuedDate) }} · {{ docTypeLabel(doc) }}
                <template v-if="doc.expiresDate">
                  · 만료 {{ formatDate(doc.expiresDate) }}</template
                >
              </span>
              <span v-if="ownsHealthCertificate(doc)" class="doc-share">
                <Share2 :size="13" />
                <template v-if="doc.activeShares?.length">
                  공유중 · {{ doc.activeShares.map((s) => s.workplaceName).join(', ') }}
                </template>
                <template v-else>공유 안 함</template>
              </span>
            </span>

            <span v-if="doc.status === 'EXPIRED'" class="badge badge--expired">만료</span>
          </button>

          <div class="doc-side">
            <template v-if="ownsHealthCertificate(doc)">
              <button type="button" class="act-btn" aria-label="공유 관리" @click="openShare(doc)">
                <Share2 :size="16" />
              </button>
              <button type="button" class="act-btn" aria-label="발급일 수정" @click="openEdit(doc)">
                <Pencil :size="16" />
              </button>
            </template>
            <button
              v-if="doc.capabilities?.canDelete"
              type="button"
              class="act-btn act-btn--danger"
              aria-label="보건증 삭제"
              @click="openDelete(doc)"
            >
              <Trash2 :size="16" />
            </button>
          </div>
        </li>
      </ul>

      <button
        v-if="hasMore"
        type="button"
        class="more-btn"
        :disabled="loadingMore"
        @click="loadMore"
      >
        {{ loadingMore ? '불러오는 중…' : '더 보기' }}
      </button>
    </template>

    <p class="notice">
      보건증은 직접 등록·공유할 수 있어요 · 근로계약서는 근무 시작 시 자동 저장되고 삭제할 수 없어요
    </p>

    <!-- 보건증 등록 -->
    <BaseBottomSheet :open="registerOpen" title="보건증 등록" @close="registerOpen = false">
      <div class="upload-form">
        <div class="file-field">
          <label class="file-drop">
            <input type="file" accept=".jpg,.jpeg,.png,.pdf" class="file-input" @change="onFile" />
            <Upload :size="18" />
            <span class="file-name">{{ fileName || '파일 선택 (jpg · png · pdf)' }}</span>
          </label>
          <p v-if="fileError" class="field-err">{{ fileError }}</p>
        </div>
        <AppDateFieldCalendar
          v-model="issuedDate"
          label="발급일"
          required
          variant="worker"
          :error="issuedError"
        />
        <p class="form-hint">만료일은 발급일을 기준으로 서버가 계산해요.</p>
      </div>
      <template #footer>
        <BaseButton variant="worker" size="lg" block :disabled="submitting" @click="submitUpload">
          {{ submitting ? '등록 중…' : '등록' }}
        </BaseButton>
      </template>
    </BaseBottomSheet>

    <!-- 보건증 공유 관리 -->
    <BaseBottomSheet :open="shareOpen" title="보건증 공유" @close="shareOpen = false">
      <div class="share-body">
        <p v-if="shareLoading" class="muted">불러오는 중…</p>
        <template v-else>
          <section class="share-sec">
            <h3 class="share-h">공유중인 지점</h3>
            <ul v-if="activeShares.length" class="share-list">
              <li v-for="s in activeShares" :key="s.shareId" class="share-row">
                <span class="wp"><MapPin :size="15" /> {{ s.workplaceName }}</span>
                <BaseButton variant="ghost" :disabled="shareBusy" @click="doRevoke(s)">
                  공유 취소
                </BaseButton>
              </li>
            </ul>
            <p v-else class="muted">아직 공유한 지점이 없어요.</p>
          </section>

          <section class="share-sec">
            <h3 class="share-h">공유할 지점</h3>
            <ul
              v-if="shareDoc?.capabilities?.canShare && availableTargets.length"
              class="share-list"
            >
              <li v-for="w in availableTargets" :key="w.workplaceId" class="share-row">
                <span class="wp">
                  <MapPin :size="15" /> {{ w.workplaceName }}
                  <small v-if="w.ownerName"> · {{ w.ownerName }}</small>
                </span>
                <BaseButton variant="worker" :disabled="shareBusy" @click="doShare(w)">
                  공유
                </BaseButton>
              </li>
            </ul>
            <p v-else-if="shareDoc?.status === 'EXPIRED'" class="muted">
              만료된 보건증은 새로 공유할 수 없어요.
            </p>
            <p v-else class="muted">공유할 수 있는 근무 예정 지점이 없어요.</p>
          </section>
        </template>
      </div>
    </BaseBottomSheet>

    <!-- 발급일 수정 -->
    <BaseModal :open="editOpen" title="발급일 수정" @close="editOpen = false">
      <AppDateFieldCalendar v-model="editDate" label="발급일" variant="worker" :error="editError" />
      <template #footer>
        <BaseButton variant="secondary" block @click="editOpen = false">취소</BaseButton>
        <BaseButton variant="worker" block :disabled="editSaving" @click="saveEdit"
          >저장</BaseButton
        >
      </template>
    </BaseModal>

    <!-- 삭제 확인 -->
    <BaseModal :open="deleteOpen" title="삭제할까요?" @close="deleteOpen = false">
      <p class="del-msg">
        <strong>{{ deleteDoc?.fileName }}</strong> 보건증을 삭제합니다.
      </p>
      <p class="del-note">공유중인 지점에서도 즉시 열람할 수 없게 돼요.</p>
      <template #footer>
        <BaseButton variant="secondary" block @click="deleteOpen = false">취소</BaseButton>
        <BaseButton variant="danger" block :disabled="deleting" @click="confirmDelete"
          >삭제</BaseButton
        >
      </template>
    </BaseModal>
  </div>
</template>

<style scoped>
.worker-docs {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}

/* 툴바(탭 + 등록 버튼) — 사장 문서함과 동일 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
}
.tabs {
  display: inline-flex;
  gap: var(--space-xs);
  padding: 4px;
  background: var(--color-bg);
  border-radius: var(--radius-pill);
}
.tab {
  padding: var(--space-xs) var(--space-md);
  border-radius: var(--radius-pill);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-sub);
}
.tab.active {
  background: var(--color-worker);
  color: var(--color-on-primary);
}
.upload-btn {
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  gap: 4px;
  padding: var(--space-xs) var(--space-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-worker);
  background: var(--color-surface);
}

.loading {
  margin-top: var(--space-xl);
  text-align: center;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}

/* 카드 리스트 — 사장 문서함과 동일한 썸네일 카드 */
.doc-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}
.doc-card {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-md);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.doc-main {
  display: flex;
  flex: 1;
  min-width: 0;
  align-items: center;
  gap: var(--space-md);
  text-align: left;
}
.thumb {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  color: var(--color-worker);
  background: var(--color-worker-weak);
  border-radius: var(--radius-sm);
}
.doc-info {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.doc-name {
  overflow: hidden;
  font-size: var(--text-md);
  font-weight: var(--weight-medium);
  color: var(--color-text);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.doc-meta {
  font-size: var(--text-sm);
  color: var(--color-text-sub);
  word-break: keep-all;
}
.doc-share {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: var(--text-sm);
  color: var(--color-worker);
}
.badge {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  padding: 2px var(--space-xs);
  border-radius: var(--radius-pill);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
}
.badge--expired {
  color: var(--color-danger);
  background: var(--color-danger-bg);
}

.doc-side {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: var(--space-xs);
}
.act-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--radius-sm);
  color: var(--color-text-sub);
}
.act-btn--danger {
  color: var(--color-danger);
}

.more-btn {
  width: 100%;
  padding: var(--space-sm);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-surface);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-sub);
}

.notice {
  padding: var(--space-md);
  background: var(--color-bg);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
  word-break: keep-all;
}

/* 등록 시트 */
.upload-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}
.file-drop {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-lg);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-sm);
  color: var(--color-text-sub);
  cursor: pointer;
}
.file-input {
  display: none;
}
.file-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.field-err {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  color: var(--color-danger);
}
.form-hint {
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}

/* 공유 시트 */
.share-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-xl);
}
.share-h {
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-text-sub);
}
.share-list {
  margin-top: var(--space-sm);
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}
.share-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-sm);
}
.wp {
  display: inline-flex;
  align-items: center;
  gap: var(--space-xs);
  font-size: var(--text-md);
  color: var(--color-text);
}
.wp small {
  /* 크기를 명시한다 — 이전에는 Bootstrap 의 small{font-size:.875em} 에 기대고 있었다(#406). */
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.muted {
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}

/* 삭제 모달 */
.del-msg {
  font-size: var(--text-md);
  color: var(--color-text);
}
.del-note {
  margin-top: var(--space-sm);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
</style>
