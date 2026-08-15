<script setup>
/**
 * [D] 사장 문서 뷰어  ·  /owner/documents/:documentId  ·  OWNER(계약 당사자 / 공유 수신)
 * 이미지·PDF 인앱 열람 + 다운로드. 읽기 전용이며 수정·삭제·재공유 동작은 없다.
 * 연계 API: GET /documents/{id}(?workCaseId) · GET /documents/{id}/file
 *   →  @/services/documents · @/composables/useDocumentPreview
 * 공유받은 보건증은 목록이 준 workCaseId 를 Query 로 그대로 넘겨야 한다 — 서버는 관계를
 * 자동 선택하지 않고, 없거나 틀리면 fallback 없이 404 다.
 * 접근 권한(계약 당사자 / 유효 공유 대상)은 서버가 최종 검증한다.
 */
import { Download, FileText, Image as ImageIcon } from 'lucide-vue-next'
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import AppBackHeader from '@/components/common/AppBackHeader.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { useDocumentPreview } from '@/composables/useDocumentPreview'
import { getDocument } from '@/services/documents'
import { useUiStore } from '@/stores/ui'
import { DOC_IMAGE_MIME_TYPES, DOC_TYPE } from '@/utils/constants'
import { formatDate } from '@/utils/format'

const route = useRoute()
const ui = useUiStore()

const documentId = Number(route.params.documentId)
const workCaseId = route.query.workCaseId ? Number(route.query.workCaseId) : undefined

const doc = ref(null)
const loading = ref(true)
const loadError = ref(null)

const { previewUrl: viewUrl, downloading, loadPreview, downloadDocument } = useDocumentPreview()

const docTypeLabel = computed(() => DOC_TYPE[doc.value?.docType]?.label ?? '문서')
const isImage = computed(() => DOC_IMAGE_MIME_TYPES.includes(doc.value?.mimeType))

onMounted(async () => {
  try {
    doc.value = await getDocument(documentId, { workCaseId })
    await loadPreview(documentId)
  } catch (error) {
    loadError.value = error
    ui.toast(documentErrorMessage(error), { type: 'danger' })
  } finally {
    loading.value = false
  }
})

// 서버는 비가시 문서·비당사자·철회·만료를 모두 404 로 통일해 존재를 숨긴다.
function documentErrorMessage(error) {
  return error?.response?.status === 404
    ? '문서를 볼 수 없어요. 공유가 취소되었거나 근무 관계가 끝났을 수 있어요.'
    : '문서를 불러오지 못했어요.'
}

async function onDownload() {
  try {
    await downloadDocument(documentId, doc.value?.fileName)
  } catch (error) {
    ui.toast(documentErrorMessage(error), { type: 'danger' })
  }
}
</script>

<template>
  <div class="sub-page">
    <AppBackHeader :title="doc?.fileName || '문서 보기'">
      <template v-if="doc" #action>
        <button
          type="button"
          class="download-btn"
          aria-label="다운로드"
          :disabled="downloading"
          @click="onDownload"
        >
          <Download :size="20" />
        </button>
      </template>
    </AppBackHeader>

    <main class="screen-body">
      <p v-if="loading" class="loading">불러오는 중…</p>

      <EmptyState v-else-if="loadError" :message="documentErrorMessage(loadError)" />

      <EmptyState v-else-if="!doc" message="문서를 찾을 수 없어요." />

      <template v-else>
        <p class="meta-line">
          {{ docTypeLabel }} · 발급 {{ formatDate(doc.issuedDate) }}
          <template v-if="doc.sharedByName"> · 공유자 {{ doc.sharedByName }}</template>
          <template v-if="doc.expiresDate"> · 만료 예정 {{ formatDate(doc.expiresDate) }}</template>
        </p>

        <div class="viewer">
          <img v-if="viewUrl && isImage" :src="viewUrl" :alt="doc.fileName" />
          <iframe v-else-if="viewUrl" :src="viewUrl" :title="doc.fileName" class="pdf-frame" />
          <div v-else class="viewer-placeholder">
            <ImageIcon v-if="isImage" :size="40" />
            <FileText v-else :size="40" />
            <p>미리보기를 불러오지 못했어요.</p>
          </div>
        </div>

        <p class="access-note">
          공유받은 보건증은 알바생이 공유를 취소하거나 근무가 끝나면 더 이상 열람할 수 없어요.
        </p>
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
.download-btn {
  color: var(--color-text);
}
.download-btn:disabled {
  color: var(--color-text-sub);
}

.meta-line {
  margin-bottom: var(--space-md);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
  word-break: keep-all;
}

.viewer {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 360px;
  overflow: hidden;
  background: var(--color-bg);
  border-radius: var(--radius-md);
}
.viewer img {
  width: 100%;
  max-height: 480px;
  object-fit: contain;
}
.pdf-frame {
  width: 100%;
  height: 480px;
  border: none;
}
.viewer-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-xl);
  color: var(--color-text-sub);
  text-align: center;
}

.access-note {
  margin-top: var(--space-md);
  padding: var(--space-md);
  background: var(--color-bg);
  border-radius: var(--radius-md);
  font-size: var(--text-sm);
  color: var(--color-text-sub);
  word-break: keep-all;
}
</style>
