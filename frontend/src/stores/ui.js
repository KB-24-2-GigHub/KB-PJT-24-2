import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 전역 UI 상태 — 토스트 큐 · 처리 중 전체화면 로딩.
 *
 * 라우터 가드(G2 역할 안내 등)와 화면 어디서든 `ui.toast('메시지')` 로 호출한다.
 * 렌더링은 App.vue 의 <ToastHost/>·<LoadingOverlay/> 가 담당한다.
 *
 * 로딩을 각 화면 컴포넌트에 지역 상태로 두면, 충전·출금처럼 성공 뒤 즉시
 * router.replace/back 으로 그 화면 자체가 unmount 되면서 로딩도 같이 사라져 최소
 * 노출 시간을 보장할 수 없다(Teleport 도 소유 컴포넌트가 unmount 되면 같이 사라진다).
 * App.vue 에 한 번만 배치된 이 스토어 상태를 써야 라우팅과 무관하게 유지된다.
 */
export const useUiStore = defineStore('ui', () => {
  const toasts = ref([]) // [{ id, message, type }]
  let seq = 0

  /**
   * 토스트 표시.
   * @param {string} message
   * @param {object} options type('info'|'success'|'warning'|'danger'), duration(ms)
   */
  function toast(message, { type = 'info', duration = 2500 } = {}) {
    const id = ++seq
    toasts.value.push({ id, message, type })
    if (duration > 0) setTimeout(() => remove(id), duration)
    return id
  }

  function remove(id) {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }

  const LOADING_MIN_VISIBLE_MS = 500
  const loading = ref(false)
  const loadingMessage = ref('')
  let loadingShownAt = 0
  let loadingHideTimer = null

  /** 처리 중 전체화면 로딩 시작. 화면이 곧 unmount 돼도(router.replace 등) 유지된다. */
  function startLoading(message = '처리하고 있어요…') {
    clearTimeout(loadingHideTimer)
    loadingHideTimer = null
    loadingMessage.value = message
    loadingShownAt = Date.now()
    loading.value = true
  }

  /** 최소 노출 시간(LOADING_MIN_VISIBLE_MS)을 채운 뒤에 로딩을 끈다. */
  function stopLoading() {
    const remaining = LOADING_MIN_VISIBLE_MS - (Date.now() - loadingShownAt)
    if (remaining <= 0) {
      loading.value = false
      return
    }
    loadingHideTimer = setTimeout(() => {
      loading.value = false
      loadingHideTimer = null
    }, remaining)
  }

  return { toasts, toast, remove, loading, loadingMessage, startLoading, stopLoading }
})
