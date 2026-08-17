/**
 * CSS 자산 참조 가드.
 *
 * `assets/**` 의 CSS 가 `url()` 로 가리키는 로컬 파일이 실제로 존재하는지 검사한다.
 *
 * 이 검사가 필요한 이유(#406): Vite 는 CSS 의 `url()` 이 없는 파일을 가리켜도 **빌드를
 * 통과시키고 원문을 그대로 내보낸다.** 그래서 `fonts.css` 가 저장소에 없는 `.woff2` 3개를
 * 참조하는 동안에도 빌드는 성공했고, 런타임에만 `/assets/fonts/Pretendard-*.woff2` 404 가
 * 3건씩 났다. `@font-face` 의 ttf 폴백이 받쳐줘서 화면상으로는 정상이라 아무도 눈치채지
 * 못했다.
 *
 * 즉 이 유형은 **빌드도 화면도 잡지 못한다.** 그래서 원본 CSS 를 직접 읽어 검사한다.
 *
 * 검사 대상에서 제외하는 것:
 *   - `data:` · `http(s):` · 프로토콜 상대(`//`) — 로컬 파일이 아니다.
 *   - 절대경로(`/...`) — `public/` 기준이라 CSS 위치로 해석하면 안 된다.
 *   - `var(...)` 를 포함한 동적 url — 정적으로 경로를 확정할 수 없다.
 *
 * 이 가드가 **막지 못하는 것**: `assets/**` 의 CSS 파일만 읽는다. 작성 시점 기준으로 SFC 의
 * `<style>` 블록에는 `url()` 이 하나도 없어 범위가 곧 전체지만, 나중에 SFC 에서 자산을
 * 참조하면 그 참조는 보지 못한다. 그때는 여기 수집 범위를 함께 넓혀야 한다.
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'

const HERE = dirname(fileURLToPath(import.meta.url))

/** `assets/` 아래 모든 CSS 파일 */
function cssFiles(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) cssFiles(full, out)
    else if (entry.endsWith('.css')) out.push(full)
  }
  return out
}

/** 정적으로 경로가 확정되는 로컬 `url()` 참조만 추린다. */
function localUrlReferences(css) {
  const refs = []
  for (const match of css.matchAll(/url\(\s*(['"]?)([^'")]+)\1\s*\)/g)) {
    const target = match[2].trim()
    if (!target) continue
    if (/^(data:|https?:|\/\/)/.test(target)) continue // 원격·인라인
    if (target.startsWith('/')) continue // public/ 기준 절대경로
    if (target.includes('var(') || target.includes('#{')) continue // 동적
    refs.push(target)
  }
  return refs
}

describe('CSS 자산 참조', () => {
  const files = cssFiles(HERE)

  it('검사할 CSS 와 url() 참조를 실제로 찾아낸다', () => {
    // 수집이 조용히 깨져 빈 목록이 되면 아래 검사가 무의미하게 통과한다.
    expect(files.length).toBeGreaterThan(0)
    const total = files.reduce(
      (sum, file) => sum + localUrlReferences(readFileSync(file, 'utf8')).length,
      0
    )
    expect(total).toBeGreaterThan(0)
  })

  it('url() 이 가리키는 로컬 파일이 모두 존재한다', () => {
    const missing = []

    for (const file of files) {
      for (const target of localUrlReferences(readFileSync(file, 'utf8'))) {
        // 경로는 그 CSS 파일 기준 상대경로다.
        const resolved = resolve(dirname(file), target.split('?')[0].split('#')[0])
        if (!existsSync(resolved)) {
          missing.push(`${relative(HERE, file).replace(/\\/g, '/')} → ${target}`)
        }
      }
    }

    expect(
      missing,
      `CSS 가 존재하지 않는 파일을 url() 로 가리키고 있습니다.\n` +
        `Vite 는 이 경우 빌드를 통과시키고 런타임 404 만 남기므로 여기서 막습니다.\n` +
        missing.map((m) => `  ${m}`).join('\n')
    ).toEqual([])
  })
})
