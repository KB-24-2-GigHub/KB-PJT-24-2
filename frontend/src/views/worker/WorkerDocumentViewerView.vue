<script setup>
/**
 * [G] 알바생 문서 뷰어  ·  /worker/documents/:documentId  ·  WORKER(소유자)
 * 이미지·PDF 인앱 열람 + 다운로드. 보건증 만료 예정일(발급일+1년) 표시.
 * 연계 API: GET /documents/{id}/file  →  @/composables/useDocumentPreview
 * route.params.documentId 사용. 공통: @/utils/format (formatDate).
 */
import { Download, FileText } from 'lucide-vue-next'
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import AppBackHeader from '@/components/common/AppBackHeader.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { useDocumentPreview } from '@/composables/useDocumentPreview'
import { documentFileUrl, listDocuments } from '@/services/documents'
import { useUiStore } from '@/stores/ui'
import { DOC_TYPE } from '@/utils/constants'
import { formatDate } from '@/utils/format'

const route = useRoute()
const ui = useUiStore()

const documentId = Number(route.params.documentId)
const doc = ref(null)
const loading = ref(true)
const loadError = ref(null)

const docTypeLabel = computed(() => DOC_TYPE[doc.value?.docType]?.label ?? '문서')
const isHealthCert = computed(() => doc.value?.docType === 'HEALTH_CERT')
const isPdf = computed(() => (doc.value?.fileExt ?? '').toLowerCase() === 'pdf')
const { previewUrl: fileUrl, loadPreview } = useDocumentPreview()
const downloadUrl = computed(() => (doc.value ? documentFileUrl(documentId, 'download') : ''))

onMounted(async () => {
  try {
    const { content } = await listDocuments()
    doc.value = (content ?? []).find((d) => d.documentId === documentId) ?? null
    if (doc.value) await loadPreview(documentId)
  } catch (error) {
    loadError.value = error
    ui.toast(
      error?.code === 'FEATURE_UNAVAILABLE'
        ? '문서 보기는 현재 준비 중인 기능입니다.'
        : '문서를 불러오지 못했어요.',
      { type: error?.code === 'FEATURE_UNAVAILABLE' ? 'info' : 'danger' }
    )
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="sub-page">
    <AppBackHeader title="문서 보기" />
    <main class="screen-body">
      <p v-if="loading" class="loading">불러오는 중…</p>

      <EmptyState
        v-else-if="loadError"
        :message="
          loadError.code === 'FEATURE_UNAVAILABLE'
            ? '문서 보기는 현재 준비 중인 기능입니다.'
            : '문서를 불러오지 못했어요.'
        "
      />

      <EmptyState v-else-if="!doc" message="문서를 찾을 수 없습니다." />

      <template v-else>
        <section class="meta-card">
          <div class="meta-head">
            <span class="doc-type">{{ docTypeLabel }}</span>
            <h1 class="doc-name">{{ doc.fileName }}</h1>
          </div>
          <dl class="meta-list">
            <div class="meta-row">
              <dt>발급일</dt>
              <dd>{{ formatDate(doc.issuedDate) }}</dd>
            </div>
            <div v-if="isHealthCert && doc.expiryDate" class="meta-row">
              <dt>만료 예정</dt>
              <dd>{{ formatDate(doc.expiryDate) }}</dd>
            </div>
          </dl>
        </section>

        <div class="preview">
          <iframe
            v-if="fileUrl && isPdf"
            :src="fileUrl"
            class="preview-frame"
            title="문서 미리보기"
          />
          <img v-else-if="fileUrl" :src="fileUrl" alt="문서 미리보기" class="preview-img" />
          <div v-else class="preview-empty">
            <FileText :size="40" />
            <p>미리보기는 서버 연동 후 제공돼요.</p>
            <p class="preview-file">{{ doc.fileName }}.{{ doc.fileExt }}</p>
          </div>
        </div>

        <a v-if="downloadUrl" :href="downloadUrl" class="download-btn" download>
          <Download :size="18" />
          다운로드
        </a>
        <p v-else class="download-hint">다운로드는 서버 연동 후 제공돼요.</p>
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
.preview {
  margin-top: var(--space-lg);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow: hidden;
  background: var(--color-bg);
}
.preview-frame {
  display: block;
  width: 100%;
  height: 60vh;
  border: none;
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
}
.download-btn {
  display: flex;
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
.download-hint {
  margin-top: var(--space-lg);
  text-align: center;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
</style>
