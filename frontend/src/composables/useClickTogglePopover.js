import { onMounted, onUnmounted, ref, useId } from 'vue'

/**
 * 클릭으로 열고 닫는 안내 팝오버 공통 로직(호버 아님).
 *
 * 예치중·근무 경과 예상금액·안심지갑 잔액 안내가 모두 같은 패턴(바깥 클릭·Escape로 닫힘)을
 * 각자 복붙해 써 왔다(#487 리뷰) — 여기 한 곳으로 모아 이후 수정(터치 이벤트, 포커스 트랩 등)이
 * 필요할 때 한 번만 고치면 되게 한다.
 *
 * 반환한 rootEl을 팝오버를 감싸는 요소(또는 그 상위)에 ref로 붙여야 바깥 클릭 판정이 된다.
 */
export function useClickTogglePopover() {
  const rootEl = ref(null)
  const open = ref(false)
  const id = useId()

  function toggle() {
    open.value = !open.value
  }

  function close() {
    open.value = false
  }

  function onDocumentClick(e) {
    if (!open.value) return
    if (!rootEl.value?.contains(e.target)) open.value = false
  }

  function onKeydown(e) {
    if (e.key === 'Escape') open.value = false
  }

  onMounted(() => {
    document.addEventListener('click', onDocumentClick)
    document.addEventListener('keydown', onKeydown)
  })

  onUnmounted(() => {
    document.removeEventListener('click', onDocumentClick)
    document.removeEventListener('keydown', onKeydown)
  })

  return { rootEl, open, id, toggle, close }
}
