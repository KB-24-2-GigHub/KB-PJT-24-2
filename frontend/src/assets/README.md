# Frontend assets 가이드

이 문서는 현재 `frontend/src/assets/`의 연결 방식과 자산 import 규칙만 설명합니다. 내용이 코드와 다르면 실제 import, [`vite.config.js`](../../vite.config.js), CSS를 기준으로 문서를 함께 갱신합니다.

## 현재 연결

- [`main.js`](../main.js)가 [`main.css`](main.css)를 한 번 불러옵니다.
- `main.css`는 [`fonts.css`](fonts.css)와 [`base.css`](base.css)를 불러옵니다.
- Pretendard 폰트 파일은 `fonts/`에 Git으로 추적되어 있어 별도 다운로드가 필요 없습니다.
- `lucide-vue-next`와 `vite-svg-loader`는 [`package.json`](../../package.json)과 lockfile에 이미 포함되어 있습니다.

최초 설치는 [Frontend README](../../README.md)에 따라 `npm ci`만 실행합니다. 의존성을 추가하거나 변경할 때는 [의존성 명세](../../../docs/DEPENDENCY_SPECIFICATION.md)의 절차를 따릅니다.

## 폴더 역할

```text
assets/
├── base.css       공통 색상·간격·타이포그래피 토큰과 기본 스타일
├── fonts.css      Pretendard @font-face
├── main.css       전역 스타일 진입점
├── fonts/         Git으로 추적하는 폰트 파일
└── images/
    ├── logo/      역할별(owner/worker) GigHub PNG 로고
    ├── badges/    역할·등급별(lv0~3) PNG 뱃지
    └── banks/     은행 선택 화면에서 사용하는 PNG 로고
```

## 현재 사용 방식

| 자산            | 현재 기준                                                                                                               |
| --------------- | ----------------------------------------------------------------------------------------------------------------------- |
| 공통 스타일     | 재사용되는 색상·간격·타이포그래피는 `base.css`의 CSS 변수를 우선 사용                                                   |
| GigHub PNG 로고 | 역할별로 색이 입혀진 파일을 두고, 화면의 role/level 에 따라 import한 URL을 `<img src>`로 전환                           |
| 등급 뱃지 PNG   | [`TrustBadge.vue`](../components/common/TrustBadge.vue)처럼 import 후 `<img>`에서 사용                                  |
| 은행 PNG        | [`constants.js`](../utils/constants.js)에서 import하고 [`BankSelect.vue`](../components/wallet/BankSelect.vue)에서 표시 |
| 일반 UI 아이콘  | 파일을 새로 복제하지 않고 `lucide-vue-next` 컴포넌트를 우선 재사용                                                      |

컴포넌트 하나에만 필요한 크기와 레이아웃 값은 scoped CSS에 둘 수 있습니다. 여러 화면에서 같은 의미로 반복되는 값은 기존 토큰을 재사용하고, 적합한 토큰이 없으면 `base.css`에 추가합니다.

## 변경 규칙

- 기존 자산과 아이콘을 먼저 검색해 중복 파일을 만들지 않습니다.
- SVG를 Vue 컴포넌트로 제어할 때는 일반 import를, `<img>`의 `src`로 사용할 때는 `?url` import를 사용합니다.
- 현재 은행 PNG는 애플리케이션에서 사용 중인 자산입니다. 외부 브랜드 자산을 새로 추가하거나 교체할 때는 팀이 승인한 파일과 사용 조건을 확인합니다.
- 파일을 추가·이동·교체하면 사용하는 import와 화면을 함께 확인합니다.
- 폴더 역할이나 공통 사용 방식이 바뀐 경우에만 이 문서를 갱신합니다.
