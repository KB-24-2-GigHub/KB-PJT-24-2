# Gig Hub Frontend

Gig Hub의 Vue.js SPA입니다. Vue 3, Vite, Vue Router, Pinia, Axios, Bootstrap을 사용하며 JavaScript로 작성합니다.

## 요구 환경

- Node.js `20.19.0` 이상, `25.0.0` 미만
- npm `11` 이상, `12` 미만

## 최초 설치

`package-lock.json`이 있으므로 팀원은 개별 패키지를 설치하지 않고 다음 명령을 사용합니다.

```powershell
Set-Location frontend
npm ci
```

일반 설치는 `npm ci`만 사용합니다. 직접 의존성을 추가하거나 변경할 때는 [의존성 명세](../docs/DEPENDENCY_SPECIFICATION.md)의 변경 절차를 따릅니다.

## 실행

```powershell
npm run dev
```

기본 개발 주소는 Vite가 터미널에 표시합니다. `/api` 요청은 `.env`의 `DEV_PROXY_TARGET`으로 전달되며 기본값은 `http://localhost:8080`입니다.

환경 파일은 다음과 같이 준비합니다.

```powershell
Copy-Item .env.example .env
```

## 검증

```powershell
npm run lint
npm run test:run
npm run build
```

커밋 훅은 스테이징된 프런트엔드 파일에만 Prettier를 자동 적용한 뒤 ESLint를 실행합니다.
운영체제별 LF/CRLF는 모두 허용합니다. 프로젝트 전체를 확인하는 아래 검사는 선택 사항이며
루트 `npm run check`의 필수 통과 조건은 아닙니다.

```powershell
npm run format:check
```

저장소 루트에서는 다음 명령으로 전체 Guardrail·하네스, Frontend Vitest·Production build와
Frontend·Backend lint를 실행합니다.

```powershell
Set-Location ..
npm run check
```

## 주요 구조

```text
frontend/
  src/
    assets/       공통 스타일
    components/   재사용 UI
    router/       화면 경로와 Guard
    services/     Axios 기반 API Client
    stores/       Pinia 상태
    views/        Route 화면
  .env.example
  eslint.config.js
  package.json
  vite.config.js
```

- Vue 상태는 화면 상태를 관리하며 서버 데이터의 최종 원본으로 취급하지 않습니다.
- Axios 공통 Client는 `/api`를 사용하고 세션 Cookie와 CSRF Header를 전송할 수 있게 구성되어 있습니다.
- 로그인·권한 검사는 프론트 Router Guard만 믿지 않고 Spring Service에서도 반드시 수행해야 합니다.
- 공통 스타일과 SVG 자산 사용법은 [assets 가이드](src/assets/README.md)를 확인합니다.
