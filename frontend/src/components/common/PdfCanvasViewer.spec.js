/**
 * PDF 미리보기 계약 테스트 (#433).
 *
 * 여기서 고정하는 것은 세 가지다. 모든 Page 를 그리는지, 실패가 화면을 깨뜨리지 않고 안내로
 * 끝나는지, Blob 이 바뀌면 이전 결과가 남지 않는지.
 *
 * jsdom 에는 Canvas 2D 구현이 없어 실제 렌더링 결과를 검증할 수 없다. pdf.js 를 mock 해서
 * 호출 경계만 고정하고, 실제 렌더링 품질은 실기기 확인으로 검증한다.
 */
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const getDocument = vi.fn()
const globalWorkerOptions = {}

vi.mock('pdfjs-dist/build/pdf.mjs', () => ({
  getDocument: (...args) => getDocument(...args),
  GlobalWorkerOptions: globalWorkerOptions
}))
vi.mock('pdfjs-dist/build/pdf.worker.mjs?url', () => ({ default: 'worker-stub-url' }))

import PdfCanvasViewer from '@/components/common/PdfCanvasViewer.vue'

/** pdf.js 문서 대역. Page 수만큼 render 가 불리는지 보기 위한 최소 형태다. */
function fakePdf(numPages) {
  const destroy = vi.fn().mockResolvedValue(undefined)
  const render = vi.fn().mockReturnValue({ promise: Promise.resolve() })
  const doc = {
    numPages,
    destroy,
    getPage: vi.fn().mockResolvedValue({
      getViewport: () => ({ width: 600, height: 800 }),
      render
    })
  }
  getDocument.mockReturnValue({ promise: Promise.resolve(doc) })
  return { doc, render, destroy }
}

function pdfBlob() {
  const blob = new Blob(['%PDF-1.7'], { type: 'application/pdf' })
  // jsdom 의 Blob 에는 arrayBuffer 가 없는 경우가 있어 직접 채운다.
  blob.arrayBuffer = () => Promise.resolve(new ArrayBuffer(8))
  return blob
}

/**
 * 렌더링은 동적 import → arrayBuffer → getDocument → Page 순회로 이어진다. nextTick 을
 * 몇 번 돌리는 것으로는 이 사슬이 끝나지 않으므로 조건이 만족될 때까지 기다린다.
 */
function waitFor(assertion) {
  return vi.waitFor(assertion, { timeout: 2000 })
}

describe('PdfCanvasViewer', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // jsdom 에는 canvas 구현이 없다. 렌더링 호출 경계만 보기 위한 최소 대역이다.
    globalThis.HTMLCanvasElement.prototype.getContext = vi.fn(() => ({}))
    globalThis.HTMLCanvasElement.prototype.toDataURL = vi.fn(() => 'data:image/png;base64,stub')
  })

  /* 여러 장짜리 계약서에서 뒷장을 볼 수 없으면 미리보기의 목적을 잃는다. */
  it('문서의 모든 Page 를 그린다', async () => {
    const { doc } = fakePdf(3)
    const wrapper = mount(PdfCanvasViewer, { props: { blob: pdfBlob() } })

    await waitFor(() => expect(wrapper.findAll('img')).toHaveLength(3))

    expect(doc.getPage).toHaveBeenCalledTimes(3)
  })

  /* iframe 을 쓰지 않는 것이 이 컴포넌트의 존재 이유다. 모바일에 내장 PDF 뷰어가 없다. */
  it('iframe 을 만들지 않는다', async () => {
    fakePdf(1)
    const wrapper = mount(PdfCanvasViewer, { props: { blob: pdfBlob() } })

    await waitFor(() => expect(wrapper.find('img').exists()).toBe(true))

    expect(wrapper.find('iframe').exists()).toBe(false)
  })

  /* 미리보기 실패가 화면 전체를 막지 않는다. 호출자는 다운로드 경로를 계속 제공한다. */
  it('렌더링에 실패하면 던지지 않고 안내만 남긴다', async () => {
    getDocument.mockReturnValue({ promise: Promise.reject(new Error('broken pdf')) })
    const wrapper = mount(PdfCanvasViewer, { props: { blob: pdfBlob() } })

    await waitFor(() => expect(wrapper.text()).toContain('미리보기를 표시할 수 없어요'))

    expect(wrapper.findAll('img')).toHaveLength(0)
  })

  it('Blob 이 없으면 아무것도 그리지 않는다', async () => {
    const wrapper = mount(PdfCanvasViewer, { props: { blob: null } })

    await wrapper.vm.$nextTick()

    expect(getDocument).not.toHaveBeenCalled()
    expect(wrapper.findAll('img')).toHaveLength(0)
  })

  /*
   * 다른 문서로 바뀌면 이전 문서의 Page 가 남아 있으면 안 된다. 그리고 pdf.js 문서를 닫지
   * 않으면 Worker 쪽 자원이 계속 쌓인다.
   */
  it('Blob 이 바뀌면 이전 문서를 닫고 다시 그린다', async () => {
    const first = fakePdf(3)
    const wrapper = mount(PdfCanvasViewer, { props: { blob: pdfBlob() } })
    await waitFor(() => expect(wrapper.findAll('img')).toHaveLength(3))

    fakePdf(1)
    await wrapper.setProps({ blob: pdfBlob() })
    await waitFor(() => expect(wrapper.findAll('img')).toHaveLength(1))

    expect(first.destroy).toHaveBeenCalled()
  })
})
