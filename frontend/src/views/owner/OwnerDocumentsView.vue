<script setup>
/**
 * [D] 사장 문서함  ·  /owner/documents  ·  OWNER  (탭 화면)
 * 지점 문서: 자동 생성 계약서 + 계약서 직접 업로드 + 공유받은 보건증.
 * 지점 컨텍스트: useWorkplaceStore().selectedId (AppTopBar 의 전역 지점 select 를 그대로 구독).
 * 연계 API: GET /documents?workplaceId · POST /documents · DELETE /documents/{id}
 *   →  @/services/documents (listDocuments, uploadDocument, deleteDocument, isDocumentDeletable)
 * 규칙: 삭제는 계약서·보건증 모두 근무 종료 후만(서버 409 최종 검증). 보건증 직접 업로드 없음.
 * 공통: 카드 클릭 → /owner/documents/:documentId
 */
import { FileText, Image as ImageIcon, Lock, Plus, Trash2 } from 'lucide-vue-next'
import { storeToRefs } from 'pinia'
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import BaseButton from '@/components/common/BaseButton.vue'
import BaseModal from '@/components/common/BaseModal.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import {
  deleteDocument,
  isDocumentDeletable,
  listDocuments,
  uploadDocument
} from '@/services/documents'
import { useWorkplaceStore } from '@/stores/workplace'
import { useUiStore } from '@/stores/ui'
import { formatDate } from '@/utils/format'

const router = useRouter()
const ui = useUiStore()
const workplaceStore = useWorkplaceStore()
const { selectedId } = storeToRefs(workplaceStore)

const TABS = [
  { value: 'ALL', label: '전체' },
  { value: 'CONTRACT', label: '근로계약서' },
  { value: 'HEALTH_CERT', label: '보건증' }
]

const documents = ref([])
const loading = ref(false)
const loadError = ref(null)
const activeTab = ref('ALL')
const fileInput = ref(null)
const uploading = ref(false)

// mock 은 params 를 무시하고 전체를 돌려주므로(다른 서비스와 동일한 관행), 지점 필터는 여기서 한 번 더 건다.
// 실제 API 연동 시 서버가 workplaceId 로 이미 필터링해 내려주므로 이 filter 는 그대로 둬도 안전하다.
const documentsInWorkplace = computed(() =>
  documents.value.filter((d) => d.workplaceId === selectedId.value)
)
const filteredDocuments = computed(() => {
  if (activeTab.value === 'ALL') return documentsInWorkplace.value
  return documentsInWorkplace.value.filter((d) => d.docType === activeTab.value)
})

async function load() {
  loading.value = true
  loadError.value = null
  try {
    const res = await listDocuments({ workplaceId: selectedId.value })
    documents.value = res.content
  } catch (error) {
    loadError.value = error
    const unavailable = error?.code === 'FEATURE_UNAVAILABLE'
    ui.toast(unavailable ? '문서함은 현재 준비 중인 기능입니다.' : '문서를 불러오지 못했어요.', {
      type: unavailable ? 'info' : 'danger'
    })
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(selectedId, load)

function openViewer(documentId) {
  router.push(`/owner/documents/${documentId}`)
}

function triggerUpload() {
  fileInput.value?.click()
}

/* ---- 계약서 직접 업로드(확인 모달) ---- */
const pendingFile = ref(null)
const uploadConfirmOpen = ref(false)

// 파일 선택 시 바로 올리지 않고, 확인 모달로 한 번 더 확인받는다.
function onFileSelected(e) {
  const file = e.target.files?.[0]
  e.target.value = ''
  if (!file) return
  pendingFile.value = file
  uploadConfirmOpen.value = true
}

async function confirmUpload() {
  const file = pendingFile.value
  if (!file) return
  uploading.value = true
  try {
    const formData = new FormData()
    formData.append('docType', 'CONTRACT')
    formData.append('workplaceId', selectedId.value)
    formData.append('file', file)
    const res = await uploadDocument(formData)
    // mock 은 저장하지 않으므로, 업로드 결과를 로컬에서 즉시 반영해 화면에 보이게 한다.
    documents.value = [
      {
        documentId: res.documentId,
        docType: 'CONTRACT',
        workplaceId: selectedId.value,
        workCaseId: null,
        fileName: file.name.replace(/\.[^.]+$/, ''),
        fileExt: file.name.split('.').pop(),
        issuedDate: new Date().toISOString().slice(0, 10),
        expiryDate: null,
        source: 'OWN',
        sharedByName: null,
        workCaseStatus: null,
        createdAt: new Date().toISOString()
      },
      ...documents.value
    ]
    ui.toast('계약서를 업로드했어요.', { type: 'success' })
    uploadConfirmOpen.value = false
    pendingFile.value = null
  } catch (err) {
    ui.toast(err?.response?.data?.message || '업로드에 실패했어요.', { type: 'danger' })
  } finally {
    uploading.value = false
  }
}

/* ---- 삭제(확인 모달) ---- */
const deleteTarget = ref(null)
const deleteOpen = ref(false)
const deleting = ref(false)

function openDelete(doc) {
  deleteTarget.value = doc
  deleteOpen.value = true
}

async function confirmDelete() {
  const doc = deleteTarget.value
  if (!doc) return
  deleting.value = true
  try {
    await deleteDocument(doc.documentId)
    // 당사자별 독립 삭제(내 문서함에서만 제거) — 로컬 리스트에서만 제거해 흉내낸다.
    documents.value = documents.value.filter((d) => d.documentId !== doc.documentId)
    ui.toast('삭제했어요.', { type: 'success' })
    deleteOpen.value = false
    deleteTarget.value = null
  } catch (err) {
    // 계약서·보건증 모두 근무 종료 후에만 삭제 가능 — 서버가 최종 검증(409).
    if (err?.response?.status === 409) {
      ui.toast('진행 중인 근무가 있어 삭제할 수 없어요.', { type: 'warning' })
    } else {
      ui.toast(err?.response?.data?.message || '삭제할 수 없어요.', { type: 'danger' })
    }
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <div class="documents">
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

      <button
        type="button"
        class="upload-btn"
        :disabled="uploading || loadError?.code === 'FEATURE_UNAVAILABLE'"
        @click="triggerUpload"
      >
        <Plus :size="16" /> 계약서 직접 업로드
      </button>
      <input
        ref="fileInput"
        type="file"
        accept=".jpg,.jpeg,.png,.pdf"
        class="visually-hidden"
        @change="onFileSelected"
      />
    </div>

    <EmptyState
      v-if="!loading && loadError"
      :message="
        loadError.code === 'FEATURE_UNAVAILABLE'
          ? '문서함은 현재 준비 중인 기능입니다.'
          : '문서를 불러오지 못했어요.'
      "
    />

    <EmptyState
      v-else-if="!loading && filteredDocuments.length === 0"
      message="표시할 문서가 없어요."
    />

    <ul v-else class="doc-list">
      <li v-for="doc in filteredDocuments" :key="doc.documentId" class="doc-card">
        <button type="button" class="doc-main" @click="openViewer(doc.documentId)">
          <span class="thumb">
            <ImageIcon v-if="['jpg', 'jpeg', 'png'].includes(doc.fileExt)" :size="20" />
            <FileText v-else :size="20" />
          </span>

          <span class="doc-info">
            <span class="doc-name">{{ doc.fileName }}</span>
            <!-- 발급일(documents.issued_on) · 만료 예정일(documents.expires_on) -->
            <span class="doc-meta">
              {{ formatDate(doc.issuedDate) }} ·
              {{ doc.docType === 'CONTRACT' ? '근로계약서' : '보건증' }}
            </span>
            <span v-if="doc.docType === 'HEALTH_CERT'" class="doc-expiry">
              만료 예정 {{ formatDate(doc.expiryDate) }}
            </span>
          </span>
        </button>

        <div class="doc-side">
          <span class="badge" :class="isDocumentDeletable(doc) ? 'badge--ok' : 'badge--locked'">
            <Lock v-if="!isDocumentDeletable(doc)" :size="12" />
            {{ isDocumentDeletable(doc) ? '삭제 가능' : '삭제 잠금' }}
          </span>

          <button
            v-if="isDocumentDeletable(doc)"
            type="button"
            class="delete-btn"
            aria-label="문서 삭제"
            @click="openDelete(doc)"
          >
            <Trash2 :size="16" />
          </button>
        </div>
      </li>
    </ul>

    <p class="notice">
      문서 삭제는 해당 근무 종료 후 가능 · 공유받은 보건증을 지워도 알바생 원본은 남음 · 보건증 직접
      업로드 없음
    </p>

    <!-- 업로드 확인 -->
    <BaseModal
      :open="uploadConfirmOpen"
      title="계약서를 업로드할까요?"
      @close="uploadConfirmOpen = false"
    >
      <p class="modal-msg">
        <strong>{{ pendingFile?.name }}</strong> 파일을 계약서로 등록합니다.
      </p>
      <template #footer>
        <BaseButton variant="secondary" block @click="uploadConfirmOpen = false">취소</BaseButton>
        <BaseButton variant="owner" block :disabled="uploading" @click="confirmUpload">
          {{ uploading ? '업로드 중…' : '업로드' }}
        </BaseButton>
      </template>
    </BaseModal>

    <!-- 삭제 확인 -->
    <BaseModal :open="deleteOpen" title="삭제할까요?" @close="deleteOpen = false">
      <p class="modal-msg">
        <strong>{{ deleteTarget?.fileName }}</strong> 문서를 삭제합니다.
      </p>
      <p class="modal-note">근무가 종료된 계약서만 삭제할 수 있어요.</p>
      <template #footer>
        <BaseButton variant="secondary" block @click="deleteOpen = false">취소</BaseButton>
        <BaseButton variant="danger" block :disabled="deleting" @click="confirmDelete">
          {{ deleting ? '삭제 중…' : '삭제' }}
        </BaseButton>
      </template>
    </BaseModal>
  </div>
</template>

<style scoped>
.documents {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}

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
  background: var(--color-primary);
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
  color: var(--color-owner);
  background: var(--color-surface);
}
.upload-btn:disabled {
  opacity: 0.5;
}

.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}

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
  color: var(--color-owner);
  background: var(--color-owner-weak);
  border-radius: var(--radius-sm);
}
.doc-info {
  display: flex;
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
/* 만료일은 놓치면 안 되는 정보라 한 줄 내려 주의 색으로 강조한다. */
.doc-expiry {
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  color: var(--color-warning);
  word-break: keep-all;
}

.doc-side {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  gap: var(--space-xs);
}
.badge {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px var(--space-sm);
  border-radius: var(--radius-pill);
  font-size: var(--text-sm);
  white-space: nowrap;
}
.badge--ok {
  color: var(--color-success);
  background: var(--color-success-bg);
}
.badge--locked {
  color: var(--color-text-sub);
  background: var(--color-bg);
}
.delete-btn {
  color: var(--color-danger);
}

.notice {
  padding: var(--space-md);
  background: var(--color-bg);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}

.modal-msg {
  font-size: var(--text-md);
  color: var(--color-text);
}
.modal-note {
  margin-top: var(--space-sm);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
</style>
