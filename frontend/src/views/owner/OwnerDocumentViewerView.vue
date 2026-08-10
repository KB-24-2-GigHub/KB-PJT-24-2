<script setup>
/**
 * [D] 사장 문서 뷰어  ·  /owner/documents/:documentId  ·  OWNER(소유/공유 수신)
 * 이미지·PDF 인앱 열람 + 다운로드. 공유받은 보건증은 발급일·만료 예정일 표시.
 * 연계 API: GET /documents/{id}/file  →  @/services/documents (documentFileUrl)
 * 문서 메타데이터는 명세상 단건 조회 API가 없어 목록(GET /documents) 결과에서 찾는다.
 * 접근 권한(work_case 당사자 / 유효 공유 대상)은 서버가 최종 검증 — 프론트는 응답 기준 렌더링만.
 */
import { Download, FileText, Image as ImageIcon } from 'lucide-vue-next'
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import AppBackHeader from '@/components/common/AppBackHeader.vue'
import EmptyState from '@/components/common/EmptyState.vue'
import { documentFileUrl, listDocuments } from '@/services/documents'
import { useUiStore } from '@/stores/ui'
import { formatDate } from '@/utils/format'

const route = useRoute()
const ui = useUiStore()

const doc = ref(null)
const loading = ref(true)
const loadError = ref(null)

const viewUrl = ref('')
const downloadUrl = ref('')

onMounted(async () => {
  try {
    const documentId = Number(route.params.documentId)
    const res = await listDocuments()
    doc.value = res.content.find((d) => d.documentId === documentId) ?? null
    if (doc.value) {
      viewUrl.value = documentFileUrl(documentId, 'view')
      downloadUrl.value = documentFileUrl(documentId, 'download')
    }
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

function onDownload() {
  if (!downloadUrl.value) {
    ui.toast('다운로드는 실제 API 연동 후 사용할 수 있어요.', { type: 'info' })
    return
  }
  window.open(downloadUrl.value, '_blank')
}
</script>

<template>
  <div class="sub-page">
    <AppBackHeader :title="doc?.fileName || '문서 보기'">
      <template #action>
        <button type="button" class="download-btn" aria-label="다운로드" @click="onDownload">
          <Download :size="20" />
        </button>
      </template>
    </AppBackHeader>

    <main class="screen-body">
      <EmptyState
        v-if="!loading && loadError"
        :message="
          loadError.code === 'FEATURE_UNAVAILABLE'
            ? '문서 보기는 현재 준비 중인 기능입니다.'
            : '문서를 불러오지 못했어요.'
        "
      />

      <EmptyState v-else-if="!loading && !doc" message="문서를 찾을 수 없어요." />

      <template v-else-if="doc">
        <p class="meta-line">
          <template v-if="doc.docType === 'HEALTH_CERT'">
            공유자: {{ doc.sharedByName }} · 발급 {{ formatDate(doc.issuedDate) }} · 만료 예정
            {{ formatDate(doc.expiryDate) }}
          </template>
          <template v-else> 발급 {{ formatDate(doc.issuedDate) }} </template>
        </p>

        <div class="viewer">
          <img
            v-if="viewUrl && ['jpg', 'jpeg', 'png'].includes(doc.fileExt)"
            :src="viewUrl"
            :alt="doc.fileName"
          />
          <iframe v-else-if="viewUrl" :src="viewUrl" :title="doc.fileName" class="pdf-frame" />
          <div v-else class="viewer-placeholder">
            <ImageIcon v-if="['jpg', 'jpeg', 'png'].includes(doc.fileExt)" :size="40" />
            <FileText v-else :size="40" />
            <p>실제 파일 미리보기는 API 연동 후 표시돼요.</p>
          </div>
        </div>

        <p class="access-note">
          접근 권한: work_case 당사자(계약서) 또는 유효 공유 대상(보건증) — 서버 최종 검증
        </p>
      </template>
    </main>
  </div>
</template>

<style scoped>
.screen-body {
  padding: var(--space-lg);
}
.download-btn {
  color: var(--color-text);
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
}
</style>
