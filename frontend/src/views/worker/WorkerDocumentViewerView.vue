<script setup>
/**
 * [G] 알바생 문서 뷰어  ·  /worker/documents/:documentId  ·  WORKER
 * 이미지·PDF 인앱 열람 + 다운로드. 만료 예정일은 서버가 계산한 expiresDate 를 그대로 쓴다
 * (발급일+1년을 화면에서 추정하지 않는다).
 * 연계 API: GET /documents/{id} · GET /documents/{id}/file
 *   →  @/services/documents · @/composables/useDocumentPreview
 * WORKER 가 보는 문서는 본인 보건증(OWN)과 본인이 당사자인 근로계약서뿐이라 상세에
 * workCaseId Query 가 필요하지 않다 — 계약서는 문서 자체가 work_case 를 갖는다.
 */
import { Download, FileText } from 'lucide-vue-next'
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import AppBackHeader from '@/components/common/AppBackHeader.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import PdfCanvasViewer from '@/components/common/PdfCanvasViewer.vue'
import { useDocumentPreview } from '@/composables/useDocumentPreview'
import { getDocument } from '@/services/documents'
import { useUiStore } from '@/stores/ui'
import { docTypeLabel, documentAccessErrorMessage, isPdfDocument } from '@/utils/document'
import { formatDate } from '@/utils/format'

const route = useRoute()
const ui = useUiStore()

// 삭제·비소유·비가시 문서는 서버가 모두 404 로 통일해 존재를 숨긴다.
const NOT_FOUND_MESSAGE = '문서를 볼 수 없어요.'

const documentId = Number(route.params.documentId)
const doc = ref(null)
const loading = ref(true)
const loadError = ref(null)

const typeLabel = computed(() => docTypeLabel(doc.value))
const isPdf = computed(() => isPdfDocument(doc.value))
const isExpired = computed(() => doc.value?.status === 'EXPIRED')
const canDownload = computed(() => doc.value?.capabilities?.canDownload === true)

const {
  previewUrl: fileUrl,
  previewBlob: fileBlob,
  downloading,
  loadPreview,
  downloadDocument
} = useDocumentPreview()

/**
 * 문서 조회와 미리보기는 실패 의미가 다르다. 파일 Stream 만 실패했을 때 문서 전체를
 * 접근 불가로 그리면, 실재하고 권한도 있는 문서를 "볼 수 없음"으로 잘못 알리게 된다.
 */
onMounted(async () => {
  try {
    doc.value = await getDocument(documentId)
  } catch (error) {
    loadError.value = error
    ui.toast(errorMessage(error), { type: 'danger' })
    return
  } finally {
    loading.value = false
  }

  try {
    await loadPreview(documentId)
  } catch (error) {
    ui.toast(errorMessage(error), { type: 'warning' })
  }
})

function errorMessage(error) {
  return documentAccessErrorMessage(error, NOT_FOUND_MESSAGE)
}

async function onDownload() {
  try {
    await downloadDocument(documentId, doc.value?.fileName)
  } catch (error) {
    ui.toast(errorMessage(error), { type: 'danger' })
  }
}
</script>

<template>
  <div class="sub-page">
    <AppBackHeader title="문서 보기" />
    <main class="screen-body">
      <p v-if="loading" class="loading">불러오는 중…</p>

      <EmptyState v-else-if="loadError" :message="errorMessage(loadError)" />

      <EmptyState v-else-if="!doc" message="문서를 찾을 수 없습니다." />

      <template v-else>
        <section class="meta-card">
          <div class="meta-head">
            <span class="doc-type">{{ typeLabel }}</span>
            <h1 class="doc-name">{{ doc.fileName }}</h1>
          </div>
          <dl class="meta-list">
            <div class="meta-row">
              <dt>발급일</dt>
              <dd>{{ formatDate(doc.issuedDate) }}</dd>
            </div>
            <div v-if="doc.expiresDate" class="meta-row">
              <dt>만료 예정</dt>
              <dd :class="{ expired: isExpired }">
                {{ formatDate(doc.expiresDate) }}
                <template v-if="isExpired">(만료됨)</template>
              </dd>
            </div>
          </dl>
        </section>

        <div class="preview">
          <PdfCanvasViewer v-if="fileBlob && isPdf" :blob="fileBlob" />
          <img
            v-else-if="fileUrl && !isPdf"
            :src="fileUrl"
            alt="문서 미리보기"
            class="preview-img"
          />
          <div v-else class="preview-empty">
            <FileText :size="40" />
            <p>미리보기를 불러오지 못했어요.</p>
            <p class="preview-file">{{ doc.fileName }}</p>
          </div>
        </div>

        <button
          v-if="canDownload"
          type="button"
          class="download-btn"
          :disabled="downloading"
          @click="onDownload"
        >
          <Download :size="18" />
          {{ downloading ? '내려받는 중…' : '다운로드' }}
        </button>
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
.meta-card {
  padding: var(--space-lg);
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
}
.doc-type {
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.doc-name {
  margin-top: var(--space-xs);
  font-size: var(--text-xl);
  font-weight: var(--weight-bold);
  color: var(--color-text);
  word-break: break-all;
}
.meta-list {
  margin-top: var(--space-md);
  padding-top: var(--space-md);
  border-top: 1px solid var(--color-border);
}
.meta-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: var(--space-xs) 0;
}
.meta-row dt {
  font-size: var(--text-md);
  color: var(--color-text-sub);
}
.meta-row dd {
  font-size: var(--text-md);
  color: var(--color-text);
}
.meta-row dd.expired {
  color: var(--color-danger);
}
.preview {
  margin-top: var(--space-lg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--color-bg);
}
.preview-img {
  display: block;
  width: 100%;
}
.preview-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-xl) var(--space-lg);
  color: var(--color-text-sub);
  text-align: center;
}
.preview-file {
  font-size: var(--text-sm);
  word-break: break-all;
}
.download-btn {
  display: flex;
  width: 100%;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  margin-top: var(--space-lg);
  padding: var(--space-md) var(--space-lg);
  border-radius: var(--radius-sm);
  background: var(--color-primary);
  color: var(--color-on-primary);
  font-size: var(--text-lg);
  font-weight: var(--weight-medium);
}
.download-btn:disabled {
  opacity: 0.6;
}
</style>
