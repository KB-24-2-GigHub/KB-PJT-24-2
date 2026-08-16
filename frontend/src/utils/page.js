/**
 * 공통 Page Envelope(`{content, page:{number,size,totalElements,totalPages}}`) 헬퍼.
 *
 * 목록 API 는 기본 20건 Page 로 내려온다. 화면이 첫 Page 만 읽으면 남은 항목이 표시도
 * 오류도 없이 사라지므로, "전부 보여줘야 하는 목록"과 "이어서 더 볼 수 있는 목록"을
 * 구분해 각각 이 헬퍼를 쓴다.
 */

/** 승인 Page 상한(Backend `PageRequests.MAX_SIZE`). 넘기면 서버가 400 으로 거부한다. */
export const MAX_PAGE_SIZE = 100

/** 다음 Page 가 남아 있는지. '더 보기'를 그릴지 판단한다. */
export function hasNextPage(page) {
  if (!page) return false
  return page.number + 1 < page.totalPages
}

/**
 * 모든 Page 를 모아 하나의 배열로 돌려준다.
 *
 * 선택지나 현재 상태처럼 "일부만 보이면 사용자가 잘못 판단하게 되는" 목록에 쓴다.
 * `fetchPage` 는 `{page, size}` 를 받아 Page Envelope 를 돌려주는 함수다.
 */
export async function collectAllPages(fetchPage) {
  const first = await fetchPage({ page: 0, size: MAX_PAGE_SIZE })
  const items = [...(first.content ?? [])]
  const totalPages = first.page?.totalPages ?? 1

  for (let page = 1; page < totalPages; page += 1) {
    const next = await fetchPage({ page, size: MAX_PAGE_SIZE })
    items.push(...(next.content ?? []))
  }
  return items
}
