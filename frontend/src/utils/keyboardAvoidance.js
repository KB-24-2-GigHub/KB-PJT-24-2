/**
 * 전체화면(Fullscreen API, `requestFullscreenSafely`) 상태에서는 모바일 키보드가 뜰 때
 * 브라우저가 레이아웃 뷰포트를 줄이지 않고 오버레이로만 얹어서, 브라우저 기본
 * "포커스 필드 자동 스크롤" 동작이 함께 사라진다. `visualViewport`의 `resize`는 전체화면
 * 여부와 무관하게 정상 발생하므로, 이를 이용해 포커스된 입력 요소를 직접 보이는 영역
 * 안으로 스크롤한다.
 *
 * 전체화면 여부를 가리지 않고 항상 켜둔다 — 비전체화면에서는 브라우저 기본 동작과
 * 겹쳐 스크롤이 한 번 더 보정될 뿐이라 부작용이 없다. `visualViewport` 미지원 환경
 * (구형 브라우저 등)에서는 조용히 아무 것도 하지 않는다.
 */
const NON_TEXT_INPUT_TYPES = new Set([
  'hidden',
  'checkbox',
  'radio',
  'button',
  'submit',
  'reset',
  'range',
  'color',
  'file',
  'image'
])

function isEditable(el) {
  if (!el || !el.tagName) return false
  if (el.readOnly || el.disabled) return false
  if (el.tagName === 'INPUT') return !NON_TEXT_INPUT_TYPES.has(el.type)
  if (el.tagName === 'TEXTAREA') return true
  return !!el.isContentEditable
}

export function setupKeyboardAvoidance() {
  const viewport = window.visualViewport
  if (!viewport) return () => {}

  let focused = null

  function scrollFocusedIntoView() {
    if (!focused || document.activeElement !== focused) return
    focused.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }

  function onFocusIn(e) {
    focused = isEditable(e.target) ? e.target : null
    // 키보드가 이미 떠 있는 상태에서 다른 필드로 옮기면 뷰포트 높이가 그대로라
    // resize 가 발생하지 않는다 — 그 경우를 위해 포커스 시점에도 직접 보정한다.
    scrollFocusedIntoView()
  }

  function onFocusOut() {
    focused = null
  }

  document.addEventListener('focusin', onFocusIn)
  document.addEventListener('focusout', onFocusOut)
  viewport.addEventListener('resize', scrollFocusedIntoView)

  return () => {
    document.removeEventListener('focusin', onFocusIn)
    document.removeEventListener('focusout', onFocusOut)
    viewport.removeEventListener('resize', scrollFocusedIntoView)
  }
}
