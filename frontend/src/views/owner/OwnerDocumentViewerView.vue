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
import { docTypeLabel, documentAccessErrorMessage, isImageDocument } from '@/utils/document'
import { formatDate } from '@/utils/format'

const route = useRoute()
const ui = useUiStore()

const NOT_FOUND_MESSAGE = '문서를 볼 수 없어요. 공유가 취소되었거나 근무 관계가 끝났을 수 있어요.'

const documentId = Number(route.params.documentId)
const workCaseId = route.query.workCaseId ? Number(route.query.workCaseId) : undefined

const doc = ref(null)
const loading = ref(true)
const loadError = ref(null)

const { previewUrl: viewUrl, downloading, loadPreview, downloadDocument } = useDocumentPreview()

const typeLabel = computed(() => docTypeLabel(doc.value))
const isImage = computed(() => isImageDocument(doc.value))
const canDownload = computed(() => doc.value?.capabilities?.canDownload === true)
const isSharedHealthCertificate = computed(
  () => doc.value?.docType === 'HEALTH_CERTIFICATE' && doc.value?.source === 'SHARED'
)

/**
 * 문서 조회와 미리보기는 실패 의미가 다르다. 파일 Stream 만 실패했을 때 문서 전체를
 * 접근 불가로 그리면, 권한도 있고 실재하는 문서를 "볼 수 없음"으로 잘못 알리게 된다.
 * 그 경우는 Metadata 를 그대로 두고 미리보기 자리만 비운다.
 */
onMounted(async () => {
  try {
    doc.value = await getDocument(documentId, { workCaseId })
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
    <AppBackHeader :title="doc?.fileName || '문서 보기'">
      <template v-if="canDownload" #action>
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

      <EmptyState v-else-if="loadError" :message="errorMessage(loadError)" />

      <EmptyState v-else-if="!doc" message="문서를 찾을 수 없어요." />

      <template v-else>
        <p class="meta-line">
          {{ typeLabel }} · 발급 {{ formatDate(doc.issuedDate) }}
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

        <p v-if="isSharedHealthCertificate" class="access-note">
          공유받은 보건증은 알바생이 공유를 취소하거나 근무가 끝나면 더 이상 열람할 수 없어요.
        </p>
        <p v-else class="access-note">
          근로계약서는 시스템이 생성한 최종본이라 수정하거나 삭제할 수 없어요.
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
