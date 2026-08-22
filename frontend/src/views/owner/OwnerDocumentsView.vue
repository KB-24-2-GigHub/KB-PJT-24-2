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
import { storeToRefs } from 'pinia'
import { onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import EmptyState from '@/components/common/EmptyState.vue'
import { listDocuments } from '@/services/documents'
import { useWorkplaceStore } from '@/stores/workplace'
import { useUiStore } from '@/stores/ui'
import { docTypeLabel } from '@/utils/document'
import { formatDate } from '@/utils/format'
import { hasNextPage } from '@/utils/page'

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
const loadingMore = ref(false)
const loadError = ref(null)
const activeTab = ref('ALL')
const nextPage = ref(0)
const hasMore = ref(false)

function query(page) {
  return {
    workplaceId: selectedId.value,
    docType: activeTab.value === 'ALL' ? undefined : activeTab.value,
    page
  }
}

/**
 * 선택 지점의 문서 첫 Page.
 *
 * 유형 필터는 화면이 아니라 승인 Query 인 docType 으로 서버에서 건다 — Page 단위(기본
 * 20건)로 내려오므로 화면에서 거르면 뒤 Page 의 문서가 조용히 사라진다.
 *
 * 지점이 정해지기 전에는 요청하지 않는다. workplaceId 없는 목록 요청은 이 사장이 접근
 * 가능한 모든 지점의 문서를 돌려주므로, 사업장 Store 가 늦게 도착하면 다른 지점 문서가
 * 섞인 화면이 잠깐 그려진다.
 */
async function load() {
  if (selectedId.value == null) {
    documents.value = []
    hasMore.value = false
    return
  }
  loading.value = true
  loadError.value = null
  try {
    const { content, page } = await listDocuments(query(0))
    documents.value = content ?? []
    nextPage.value = 1
    hasMore.value = hasNextPage(page)
  } catch (error) {
    loadError.value = error
    documents.value = []
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
    documents.value = [...documents.value, ...(content ?? [])]
    nextPage.value += 1
    hasMore.value = hasNextPage(page)
  } catch {
    ui.toast('문서를 더 불러오지 못했어요.', { type: 'danger' })
  } finally {
    loadingMore.value = false
  }
}

onMounted(() => workplaceStore.load())
watch([selectedId, activeTab], load, { immediate: true })

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

    <EmptyState
      v-if="workplaceStore.loaded && !workplaceStore.hasActiveWorkplace"
      message="등록된 사업장이 없습니다."
    >
      사업장을 먼저 등록하면 계약서와 공유받은 보건증을 볼 수 있어요.
    </EmptyState>

    <p v-else-if="loading" class="loading">불러오는 중…</p>

    <template v-else>
      <EmptyState v-if="loadError" message="문서를 불러오지 못했어요." />

      <EmptyState v-else-if="documents.length === 0" message="표시할 문서가 없어요.">
        근무가 시작되면 근로계약서가 자동 저장되고, 알바생이 공유한 보건증이 여기에 보여요.
      </EmptyState>

      <template v-else>
        <ul class="doc-list">
          <li v-for="doc in documents" :key="`${doc.documentId}-${doc.workCaseId ?? 'own'}`">
            <button type="button" class="doc-card" @click="openViewer(doc)">
              <span
                class="type-badge"
                :class="
                  doc.docType === 'HEALTH_CERTIFICATE'
                    ? 'type-badge--health'
                    : 'type-badge--contract'
                "
              >
                {{ docTypeLabel(doc) }}
              </span>

              <span class="doc-info">
                <span class="doc-name">{{ doc.fileName }}</span>
                <span class="doc-meta">
                  <span class="meta-row"
                    >발급일: {{ formatDate(doc.issuedDate) }}
                    <template v-if="doc.sharedByName"> · {{ doc.sharedByName }}</template>
                  </span>
                  <span v-if="doc.expiresDate" class="meta-row"
                    >만료일: {{ formatDate(doc.expiresDate) }}</span
                  >
                </span>
              </span>

              <span v-if="doc.status === 'EXPIRED'" class="badge badge--expired">만료</span>
            </button>
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
    </template>

    <p class="notice">
      근로계약서는 근무 종료일로부터 3년간 보관된 뒤 자동 파기돼요 · 보건증은 알바생이 공유·취소를
      관리해요 · 사장님 문서함은 열람 전용이에요
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
.type-badge {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  min-width: 76px;
  padding: var(--space-xs) var(--space-sm);
  border-radius: var(--radius-pill);
  font-size: var(--text-sm);
  font-weight: var(--weight-medium);
  white-space: nowrap;
}
.type-badge--health {
  color: var(--color-worker);
  background: var(--color-worker-weak);
}
.type-badge--contract {
  color: var(--color-owner);
  background: var(--color-owner-weak);
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
  display: flex;
  flex-direction: column;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
  word-break: keep-all;
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
</style>
