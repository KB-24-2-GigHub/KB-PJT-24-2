<script setup>
/**
 * PDF 미리보기 — 공통. 받은 Blob 의 모든 Page 를 canvas 에 그린다 (#433).
 *
 * `<iframe :src="blob:...">` 를 쓰지 않는 이유가 이 컴포넌트의 존재 이유다. Android Chrome 은
 * 임베드된 PDF 를 그리는 내장 뷰어가 없고, iOS Safari 는 blob: URL 을 iframe 에 넣은 PDF 를
 * 신뢰할 수 있게 렌더링하지 않는다. 둘 다 빈 영역으로 끝나 모바일에서는 계약서를 볼 수 없었다.
 * canvas 렌더링은 브라우저 내장 뷰어에 기대지 않으므로 데스크톱·Android·iOS 가 같은 경로로
 * 동작한다.
 *
 * 파일은 이미 인증된 요청으로 받아 둔 Blob 을 그대로 쓴다. 파일 URL 을 새 탭이나 <a href> 로
 * 여는 경로를 만들지 않는다는 #183 의 결정을 그대로 지킨다.
 */
import { onBeforeUnmount, ref, shallowRef, watch } from 'vue'

const props = defineProps({
  blob: { type: Blob, default: null }
})

const pages = ref([])
const loading = ref(false)
const failed = ref(false)

// 렌더링 중인 문서. 반응형으로 감싸면 pdf.js 내부 객체까지 Proxy 가 씌워져 느려진다.
const currentDoc = shallowRef(null)
// 늦게 끝난 이전 렌더링이 새 문서의 결과를 덮어쓰지 않도록 순번을 센다.
let renderSequence = 0

/**
 * pdf.js 는 문서 뷰어에 들어올 때만 필요하다. 정적 import 로 두면 첫 화면 번들에 들어가므로
 * 동적 import 로 분리한다. Worker 도 Vite 가 별도 chunk 로 뽑도록 ?url 로 받는다.
 */
async function loadPdfjs() {
  const pdfjs = await import('pdfjs-dist/build/pdf.mjs')
  const workerSrc = (await import('pdfjs-dist/build/pdf.worker.mjs?url')).default
  pdfjs.GlobalWorkerOptions.workerSrc = workerSrc
  return pdfjs
}

async function destroyCurrentDoc() {
  const doc = currentDoc.value
  currentDoc.value = null
  if (doc) await doc.destroy().catch(() => {})
}

/**
 * Page 를 화면 폭에 맞추고 devicePixelRatio 를 곱해 그린다.
 *
 * 배율을 곱하지 않으면 고밀도 화면에서 글자가 뭉개진다. 계약서는 읽는 문서라 흐릿하면
 * 미리보기의 목적을 잃는다. 상한을 두는 것은 큰 문서에서 canvas 메모리가 폭증하는 것을 막기
 * 위해서다. 모바일 브라우저는 canvas 크기 한계를 넘으면 조용히 빈 화면을 내놓는다.
 */
function renderScale(page, containerWidth) {
  const base = page.getViewport({ scale: 1 })
  const fit = containerWidth > 0 ? containerWidth / base.width : 1
  const density = Math.min(window.devicePixelRatio || 1, 2)
  return fit * density
}

async function render(blob) {
  const sequence = ++renderSequence
  await destroyCurrentDoc()
  pages.value = []
  failed.value = false

  if (!blob) return

  loading.value = true
  try {
    const pdfjs = await loadPdfjs()
    if (sequence !== renderSequence) return

    const data = await blob.arrayBuffer()
    if (sequence !== renderSequence) return

    const doc = await pdfjs.getDocument({ data }).promise
    if (sequence !== renderSequence) {
      await doc.destroy().catch(() => {})
      return
    }
    currentDoc.value = doc

    const containerWidth = container.value?.clientWidth || 0
    const rendered = []
    for (let pageNumber = 1; pageNumber <= doc.numPages; pageNumber += 1) {
      const page = await doc.getPage(pageNumber)
      if (sequence !== renderSequence) return

      const viewport = page.getViewport({ scale: renderScale(page, containerWidth) })
      const canvas = document.createElement('canvas')
      canvas.width = Math.floor(viewport.width)
      canvas.height = Math.floor(viewport.height)
      await page.render({ canvasContext: canvas.getContext('2d'), viewport }).promise
      if (sequence !== renderSequence) return

      rendered.push({ pageNumber, dataUrl: canvas.toDataURL('image/png') })
      // 페이지마다 붙여 첫 장을 기다리지 않게 한다. 여러 장짜리 계약서에서 체감이 크다.
      pages.value = [...rendered]
    }
  } catch {
    // 미리보기 실패가 화면 전체를 막지 않는다. 호출자가 다운로드 경로를 계속 제공한다.
    if (sequence === renderSequence) {
      pages.value = []
      failed.value = true
    }
  } finally {
    if (sequence === renderSequence) loading.value = false
  }
}

const container = ref(null)

watch(() => props.blob, render, { immediate: true })

onBeforeUnmount(async () => {
  renderSequence += 1
  await destroyCurrentDoc()
})
</script>

<template>
  <div ref="container" class="pdf-viewer">
    <p v-if="loading && pages.length === 0" class="pdf-state">미리보기를 그리는 중…</p>
    <p v-else-if="failed" class="pdf-state">미리보기를 표시할 수 없어요.</p>
    <img
      v-for="page in pages"
      :key="page.pageNumber"
      :src="page.dataUrl"
      :alt="`${page.pageNumber}쪽`"
      class="pdf-page"
    />
  </div>
</template>

<style scoped>
.pdf-viewer {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  width: 100%;
}
.pdf-state {
  padding: var(--space-xl) var(--space-lg);
  text-align: center;
  font-size: var(--text-sm);
  color: var(--color-text-sub);
}
.pdf-page {
  display: block;
  width: 100%;
  height: auto;
}
</style>
