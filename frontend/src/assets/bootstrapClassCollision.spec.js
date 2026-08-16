/**
 * Bootstrap 클래스명 충돌 가드.
 *
 * main.js 가 `bootstrap/dist/css/bootstrap.min.css` 를 전역으로 로드한다. SFC 의 scoped
 * 스타일은 `[data-v-x]` 로 **특이도만** 올릴 뿐, 자신이 선언하지 않은 속성까지 막지는
 * 못한다. 그래서 scoped 클래스 이름이 Bootstrap 이 단독으로 선언한 클래스와 겹치면
 * Bootstrap 의 나머지 속성이 그대로 새어 들어온다.
 *
 * 실제로 이 저장소에서 세 번 터졌다.
 *   - `.progress`  → `display:flex; height:1rem; overflow:hidden` 이 걸려 근무 상세의
 *                    '진행 현황' 섹션이 16px 로 잘렸다.
 *   - `.row`       → `flex-wrap:wrap` + `.row > * { width:100% }` 로 dt·dd 가 세로로 쌓였다.
 *   - `.popover`   → `max-width:276px` 로 카드 폭에 맞춘 팝오버가 잘렸다.
 *
 * 개별 화면 테스트는 이 유형을 잡지 못한다 — jsdom 은 Bootstrap CSS 를 적용하지 않아
 * 단위 테스트가 전부 통과한 채로 실제 브라우저에서만 깨진다. 그래서 렌더 결과가 아니라
 * **이름 규칙 자체**를 검증한다.
 *
 * 허용 목록은 "안 걸리는 것"이 아니라 "검토해서 괜찮다고 판단한 것"의 전체 집합이다.
 * 정확히 일치하는지 보므로, 새 충돌이 생기면 물론이고 충돌이 사라져도 실패한다
 * (허용 목록을 같이 정리하라는 뜻이다).
 *
 * 이 가드가 **막지 못하는 것**: 정적 `class="..."` 와 객체형 `:class="{ name: cond }"` 만
 * 읽는다. `:class="cond ? 'a' : 'b'"` 나 `` :class="`x--${v}`" `` 처럼 값이 런타임에
 * 정해지는 바인딩은 보지 못한다. 작성 시점 기준으로 그런 바인딩이 만드는 이름은 모두
 * `is-*`, `btn--*`, `badge--*`, `app-toast--*` 로 Bootstrap 과 겹치지 않지만, 동적
 * 바인딩으로 Bootstrap 이름을 만들면 이 검사는 통과한 채 브라우저에서만 깨진다.
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'

const HERE = dirname(fileURLToPath(import.meta.url))
const SRC = resolve(HERE, '..')
const BOOTSTRAP_CSS = resolve(SRC, '../node_modules/bootstrap/dist/css/bootstrap.min.css')

/**
 * 검토를 마치고 남겨둔 충돌.
 *
 * - `badge`          단독 규칙이 있지만 새어 드는 것은 `font-weight:700`·`white-space:nowrap`
 *                    뿐이고, 세 사용처 모두 배치·색을 직접 선언한다. 시각적 피해가 없다.
 * - `btn` 계열       BaseButton 이 Bootstrap 버튼 명명을 의도적으로 따른다. 새어 드는
 *                    `border:1px solid transparent`·`line-height:1.5` 는 무해하다.
 *                    이름을 바꾸려면 전 화면을 건드려야 해 별도 리팩터링으로 다룬다.
 *
 * `visually-hidden` 은 유일한 사용처였던 OWNER 계약서 업로드 input 이 #183 에서 사라지며
 * 목록에서 빠졌다. 다시 쓰게 되면 의도적 사용으로 되돌리면 된다.
 *
 * `.active` 는 목록에 없다 — Bootstrap 에 단독 `.active` 규칙이 자체가 없어서
 * (`.nav-link.active`, `.carousel-indicators .active` 처럼 늘 조합을 요구한다) 충돌이 아니다.
 */
const ACCEPTED = ['badge', 'btn', 'btn-primary', 'btn-secondary']

/**
 * Bootstrap 이 그 클래스 **하나만으로** 요소 자신을 스타일하는 클래스명.
 *
 * 위험한 것은 선택자 전체가 `.name` 한 compound 인 규칙이다(`.row`, `.progress`,
 * `.badge:empty`). 클래스를 붙이는 것만으로 속성이 들어온다.
 *
 * 반대로 조합이 더 필요한 규칙은 이름이 겹쳐도 매칭되지 않아 안전하다.
 *   - `.nav-link.active`        — 다른 클래스를 함께 요구
 *   - `.carousel-indicators .active` — 특정 조상을 요구
 *   - `.active > .page-link`    — `.active` 자신이 아니라 그 자식을 스타일
 */
function bootstrapBareClasses() {
  const css = readFileSync(BOOTSTRAP_CSS, 'utf8')
  const bare = new Set()

  // 선행 `}` 를 소비하면 다음 규칙의 시작 경계가 사라져 규칙을 하나 걸러 놓친다.
  // 선택자는 직전 `}` 바로 뒤부터 자연히 시작하므로 경계를 따로 잡지 않는다.
  for (const rule of css.matchAll(/([^{}]+)\{[^{}]*\}/g)) {
    const selectorList = rule[1].trim()
    // `@media ...` 등의 at-rule 전문(前文)과 선언부 잔여물은 선택자가 아니다.
    if (selectorList.startsWith('@') || selectorList.includes(';')) continue

    for (const selector of selectorList.split(',')) {
      const compounds = selector
        .trim()
        .split(/[\s>+~]+/)
        .filter(Boolean)
      if (compounds.length !== 1) continue // 조상·형제·자식을 요구하면 안전하다

      // 의사클래스·의사요소는 떼어낸다: `.badge:empty` 도 .badge 만으로 매칭된다.
      const compound = compounds[0].replace(/:{1,2}[\w-]+(\([^)]*\))?/g, '')
      const match = /^\.(-?[_a-zA-Z][\w-]*)$/.exec(compound)
      if (match) bare.add(match[1])
    }
  }
  return bare
}

function vueFiles(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) vueFiles(full, out)
    else if (entry.endsWith('.vue')) out.push(full)
  }
  return out
}

/** SFC 의 `<template>` 에서 실제로 element 에 붙는 클래스명. */
function templateClasses(source) {
  const template = /<template>([\s\S]*)<\/template>/.exec(source)
  if (!template) return []

  const body = template[1]
  const names = []

  // class="a b c" — 정적 클래스
  for (const attr of body.matchAll(/\sclass="([^"]*)"/g)) {
    for (const name of attr[1].split(/\s+/)) {
      if (name && !name.includes('{') && !name.includes('$')) names.push(name)
    }
  }
  // :class="{ 'is-active': cond, active: cond }" — 조건부 클래스의 키
  for (const attr of body.matchAll(/:class="\{([^}]*)\}"/g)) {
    for (const key of attr[1].matchAll(/(?:'([^']+)'|"([^"]+)"|([\w-]+))\s*:/g)) {
      names.push(key[1] || key[2] || key[3])
    }
  }
  return names
}

describe('Bootstrap 클래스명 충돌', () => {
  const bare = bootstrapBareClasses()

  it('가드가 검사하는 Bootstrap 단독 클래스 목록을 실제로 읽어온다', () => {
    // 파싱이 조용히 깨져 빈 집합이 되면 이 파일의 모든 검사가 무의미하게 통과한다.
    expect(bare.size).toBeGreaterThan(100)
    expect(bare.has('row')).toBe(true)
    expect(bare.has('progress')).toBe(true)
    expect(bare.has('popover')).toBe(true)
    // 항상 다른 조건을 요구하는 이름은 단독으로 잡히지 않아야 한다.
    expect(bare.has('active')).toBe(false)
  })

  it('SFC 가 쓰는 클래스 중 Bootstrap 과 겹치는 것은 검토된 목록과 정확히 일치한다', () => {
    const collisions = new Map()

    for (const file of vueFiles(SRC)) {
      for (const name of templateClasses(readFileSync(file, 'utf8'))) {
        if (!bare.has(name)) continue
        if (!collisions.has(name)) collisions.set(name, new Set())
        collisions.get(name).add(relative(SRC, file).replace(/\\/g, '/'))
      }
    }

    const found = [...collisions.keys()].sort()
    const detail = found
      .map((name) => `  .${name} → ${[...collisions.get(name)].sort().join(', ')}`)
      .join('\n')

    expect(
      found,
      `Bootstrap 이 단독 규칙으로 선언한 클래스명을 SFC 가 쓰고 있습니다.\n` +
        `scoped 스타일은 선언하지 않은 속성을 막지 못하므로 이름을 바꾸세요.\n${detail}`
    ).toEqual([...ACCEPTED].sort())
  })
})
