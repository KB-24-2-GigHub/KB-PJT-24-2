<script setup>
/**
 * [D] 사장 문서함  ·  /owner/documents  ·  OWNER  (탭 화면)
 * 지점 문서: 시스템이 생성한 근로계약서 최종본 + 알바생이 공유한 활성 보건증.
 * 지점 컨텍스트: useWorkplaceStore().selectedId (AppTopBar 의 전역 지점 select 를 그대로 구독).
 * 연계 API: GET /documents?workplaceId&docType  →  @/services/documents
 * 정책(#113·#183): OWNER 는 전부 읽기 전용이다. 계약서 업로드·삭제, 공유 보건증 수정·삭제·
 *   재공유 UI 를 노출하지 않는다 — 서버에 그 조작 자체가 없거나 항상 거부된다.
 * 공통: 카드 클릭 → /owner/documents/:documentId (공유 보건증은 workCaseId 를 함께 넘긴다)
 */
import { FileText, Image as ImageIcon } from 'lucide-vue-next'
import { storeToRefs } from 'pinia'
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import EmptyState from '@/components/common/EmptyState.vue'
import { listDocuments } from '@/services/documents'
import { useWorkplaceStore } from '@/stores/workplace'
import { useUiStore } from '@/stores/ui'
import { DOC_IMAGE_MIME_TYPES, DOC_TYPE } from '@/utils/constants'
import { formatDate } from '@/utils/format'

const router = useRouter()
const ui = useUiStore()
const workplaceStore = useWorkplaceStore()
const { selectedId } = storeToRefs(workplaceStore)

const TABS = [
  { value: 'ALL', label: '전체' },
  { value: 'EMPLOYMENT_CONTRACT', label: '근로계약서' },
  { value: 'HEALTH_CERTIFICATE', label: '보건증' }
]

const documents = ref([])
const loading = ref(false)
const loadError = ref(null)
const activeTab = ref('ALL')

// 목록은 Page 단위(기본 20건)로 내려오므로 유형 필터를 화면에서 걸면 뒤 Page 의 문서가
// 조용히 사라진다. 승인 Query 인 docType 으로 서버에서 거른다.
async function load() {
  loading.value = true
  loadError.value = null
  try {
    const { content } = await listDocuments({
      workplaceId: selectedId.value,
      docType: activeTab.value === 'ALL' ? undefined : activeTab.value
    })
    documents.value = content ?? []
  } catch (error) {
    loadError.value = error
    documents.value = []
    ui.toast('문서를 불러오지 못했어요.', { type: 'danger' })
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch([selectedId, activeTab], load)

function docTypeLabel(doc) {
  return DOC_TYPE[doc.docType]?.label ?? '문서'
}

function isImage(doc) {
  return DOC_IMAGE_MIME_TYPES.includes(doc.mimeType)
}

/**
 * 공유받은 보건증 상세는 목록이 준 workCaseId 를 반드시 함께 보내야 한다. 서버는 관계를
 * 자동 선택하지 않으므로, 이 값을 빠뜨리면 접근 권한이 있어도 404 가 된다.
 */
function openViewer(doc) {
  const needsWorkCase = doc.docType === 'HEALTH_CERTIFICATE' && doc.source === 'SHARED'
  router.push({
    path: `/owner/documents/${doc.documentId}`,
    query: needsWorkCase ? { workCaseId: String(doc.workCaseId) } : {}
  })
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
    </div>

    <p v-if="loading" class="loading">불러오는 중…</p>

    <template v-else>
      <EmptyState v-if="loadError" message="문서를 불러오지 못했어요." />

      <EmptyState v-else-if="documents.length === 0" message="표시할 문서가 없어요.">
        근무가 시작되면 근로계약서가 자동 저장되고, 알바생이 공유한 보건증이 여기에 보여요.
      </EmptyState>

      <ul v-else class="doc-list">
        <li v-for="doc in documents" :key="`${doc.documentId}-${doc.workCaseId ?? 'own'}`">
          <button type="button" class="doc-card" @click="openViewer(doc)">
            <span class="thumb">
              <ImageIcon v-if="isImage(doc)" :size="20" />
              <FileText v-else :size="20" />
            </span>

            <span class="doc-info">
              <span class="doc-name">{{ doc.fileName }}</span>
              <span class="doc-meta">
                {{ formatDate(doc.issuedDate) }} · {{ docTypeLabel(doc) }}
                <template v-if="doc.sharedByName"> · {{ doc.sharedByName }}</template>
              </span>
              <span v-if="doc.expiresDate" class="doc-expiry">
                만료 예정 {{ formatDate(doc.expiresDate) }}
              </span>
            </span>

            <span v-if="doc.status === 'EXPIRED'" class="badge badge--expired">만료</span>
          </button>
        </li>
      </ul>
    </template>

    <p class="notice">
      근로계약서는 근무 시작 시 자동 저장돼요 · 보건증은 알바생이 공유·취소를 관리해요 · 사장님
      문서함은 열람 전용이에요
    </p>
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
  background: var(--color-owner);
  color: var(--color-on-primary);
}

.loading {
  margin-top: var(--space-xl);
  text-align: center;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}

.doc-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}
.doc-card {
  display: flex;
  width: 100%;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-md);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
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
.doc-expiry {
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.badge {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  gap: 2px;
  padding: 2px var(--space-xs);
  border-radius: var(--radius-pill);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
}
.badge--expired {
  color: var(--color-danger);
  background: var(--color-danger-bg);
}

.notice {
  padding: var(--space-md);
  background: var(--color-bg);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
  word-break: keep-all;
}
</style>
