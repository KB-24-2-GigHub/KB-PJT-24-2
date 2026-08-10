# Lint, 검증 및 Git Hook 가이드

이 문서는 저장소의 Lint·Guardrail·Git Hook과 PR 전 검증 기준을 설명하는 현재 기준이다. 정확한 실행 명령과 검사 범위는 아래 설정과 스크립트를 우선한다.

| 대상                   | 진실 원본                                                          |
| ---------------------- | ------------------------------------------------------------------ |
| 루트 검사 명령         | `package.json`                                                     |
| pre-commit 분류와 실행 | `scripts/run-precommit.js`                                         |
| 기술 제약 검사         | `scripts/check-project-guardrails.js`                              |
| Frontend Lint·Format   | `frontend/package.json`, `frontend/eslint.config.js`               |
| Backend Lint           | `backend/build.gradle`, `backend/config/checkstyle/checkstyle.xml` |
| Hook 진입점            | `.husky/pre-commit`, `.husky/commit-msg`                           |

## 최초 설정과 복구

새 clone의 의존성 설치와 Hook 확인은 [시작 가이드](GETTING_STARTED.md)를 따른다. 루트 `npm ci`가 `prepare` lifecycle을 실행하므로 정상 설치 후 `npm run prepare`를 반복하지 않는다.

Husky 9의 Hook 경로가 `.husky/_`인지 확인한다.

```sh
git config --get core.hooksPath
```

Hook shim이 없거나 실행되지 않을 때만 `npm run prepare`로 `.husky/_`를 다시 생성한다. 파일 mode를 직접 바꾸거나 이미 추적 중인 Hook과 의존성을 `npx husky init`, `npm install husky`로 다시 만들지 않는다.

## 루트 검증 명령

| 명령                              | 역할                                                      |
| --------------------------------- | --------------------------------------------------------- |
| `npm run lint`                    | Frontend ESLint와 Backend Gradle `check` 실행             |
| `npm run lint:fe`                 | Frontend ESLint만 실행                                    |
| `npm run lint:be`                 | Backend Gradle `check`만 실행                             |
| `npm run format:staged`           | 현재 staged Frontend 대상만 Prettier로 수정               |
| `npm run test:harness`            | Guardrail과 pre-commit 분류 스크립트 테스트               |
| `npm run check:guardrails`        | 실제 통합 base 대비 PR 범위와 작업 트리의 Guardrail 검사  |
| `npm run check:guardrails:staged` | Git index의 staged 내용만 기술 제약 검사                  |
| `npm run check:precommit`         | 실제 pre-commit 실행 계획 수행                            |
| `npm run check`                   | 전체 Guardrail, 하네스 테스트, Frontend·Backend Lint 실행 |

`npm run check`는 Frontend build·Vitest나 Backend WAR 생성을 포함하지 않는다. 기능·의존성·패키징 변경은 해당 영역의 build와 test를 별도로 실행한다.

## 영역별 Lint와 Format

### Frontend

Frontend는 ESLint와 Prettier를 사용한다.

```sh
npm run lint:fe
npm --prefix frontend run lint:fix
npm --prefix frontend run format:check
```

- ESLint는 JavaScript와 Vue SFC의 정적 오류를 검사한다.
- Prettier는 코드 형식만 담당한다.
- pre-commit 자동 Format은 `lint-staged` 설정에 포함된 Frontend 파일만 수정한다.
- `frontend/**/*.md`는 자동 Format 대상이지만 루트와 `docs/` Markdown은 아니므로 별도로 Format, 상대 링크와 Git 추적 상태를 확인한다.

VS Code에서 저장 시 ESLint 수정을 사용하려면 다음 정도의 개인 설정을 둘 수 있다. 개인 편집기 설정은 저장소 공통 규칙으로 커밋하지 않는다.

```json
{
  "eslint.validate": ["javascript", "vue"],
  "editor.codeActionsOnSave": {
    "source.fixAll.eslint": "explicit"
  }
}
```

### Backend

Backend는 Gradle `check`와 Checkstyle을 사용한다.

```powershell
Set-Location backend
.\gradlew.bat check
```

macOS 또는 Linux에서는 다음과 같이 실행한다.

```sh
cd backend
./gradlew check
```

Gradle 실행 권한을 추적하지 못하는 환경에서는 `sh ./gradlew check`를 사용한다. `check`는 Backend 테스트와 Checkstyle을 함께 실행하며 WAR 생성은 별도 `war` 작업이다.

## 포함된 Git Hook

| Hook         | 파일                | 역할                                                     |
| ------------ | ------------------- | -------------------------------------------------------- |
| `pre-commit` | `.husky/pre-commit` | staged 경로에 맞는 Guardrail, Format과 애플리케이션 Lint |
| `commit-msg` | `.husky/commit-msg` | 커밋 메시지 형식 검사                                    |

커밋 메시지 형식과 예시는 [`COMMIT_CONVENTION.md`](COMMIT_CONVENTION.md)를 기준으로 한다. Hook은 `scripts/validate-commit-msg.js`를 통해 형식을 검사한다.

## 기술 제약 Guardrail

Guardrail은 현재 프로젝트에서 금지된 기술이 실수로 추가되는 것을 막는다.

- React와 React DOM
- Spring Boot
- `JpaRepository`, `@Entity`, Persistence API, Spring Data JPA와 `hibernate-entitymanager` 같은 JPA 지표

Guardrail이 모든 ORM 문자열을 포괄하는 것은 아니며 최종 기술 제약은 `docs/DEPENDENCY_SPECIFICATION.md`를 따른다. 문서와 GitHub Template은 금지 기술을 설명할 수 있어야 하므로 애플리케이션 검사 대상에서 제외한다. staged 검사는 작업 트리가 아니라 Git index 내용을 읽어 부분 staging에서도 실제 커밋 대상만 검사한다.

`--all`과 `--release`는 PR 전체 범위를 실제 승인된 통합 브랜치와 비교한다.

- GitHub Actions에서는 `GITHUB_BASE_REF`를 실제 PR base로 사용한다.
- 로컬 기본값은 `dev`다. 이슈 또는 승인된 Parent가 `dev2`나 `main`을 선언하면
  `GIGHUB_GUARDRAIL_BASE_REF`에 그 이름을 지정한다.
- 허용 값은 `main`, `dev`, `dev2`뿐이다. SHA, 로컬 ref, 작업 브랜치와 임의 경로는 거부한다.
- 두 환경변수가 함께 존재하면서 값이 다르면 실패한다.
- 선택된 값은 항상 `refs/remotes/origin/<base>`로 해석하므로 먼저 최신 원격 ref를 fetch한다.

```sh
git fetch origin dev2
GIGHUB_GUARDRAIL_BASE_REF=dev2 npm run check
```

Windows PowerShell에서는 현재 명령 범위에 환경변수를 지정한다.

```powershell
git fetch origin dev2
$env:GIGHUB_GUARDRAIL_BASE_REF = "dev2"
npm.cmd run check
Remove-Item Env:GIGHUB_GUARDRAIL_BASE_REF
```

## 변경 경로별 pre-commit 검사

`.husky/pre-commit`은 다음 명령을 실행한다.

```sh
npm run check:precommit
```

`scripts/run-precommit.js`는 삭제와 rename의 이전·이후 경로를 포함한 staged 경로를 분류한다.

| staged 변경                                                                                         | 실행                                                             |
| --------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| 문서 전용 경로(`docs/**`, 루트 `*.md`, 모든 `README.md`)·GitHub Template·허용된 저장소 메타데이터만 | staged Guardrail, 일치하는 staged Format; 애플리케이션 Lint 생략 |
| Frontend 애플리케이션 파일만                                                                        | staged Guardrail, 일치하는 staged Format, Frontend ESLint        |
| Backend 애플리케이션 파일만                                                                         | staged Guardrail, 일치하는 staged Format, Backend Gradle `check` |
| Frontend와 Backend 애플리케이션 파일                                                                | staged Guardrail, staged Format, 하네스 테스트, 두 영역 Lint     |
| Hook·검사 스크립트·루트 설정·분류되지 않은 경로                                                     | staged Guardrail, staged Format, 하네스 테스트, 두 영역 Lint     |

문서 전용 경로와 애플리케이션 파일을 함께 변경하면 문서는 검사 범위를 넓히지 않는다. Frontend·Backend 내부의 일반 Markdown은 해당 애플리케이션 영역으로 분류하며, 알 수 없는 경로는 검사를 생략하지 않고 두 영역 검사로 처리한다.

Frontend와 Backend Lint는 선택된 영역의 working tree 전체를 검사한다. 같은 영역의 unstaged 오류가 있으면 staged 파일과 무관하게 Hook이 실패할 수 있다.

실행이나 Format 없이 현재 staged 경로의 계획만 확인하려면 dry-run을 사용한다.

```sh
npm run check:precommit -- --dry-run
```

## PR 전 검증 기준

다음 변경은 PR을 넘기기 전에 루트 전체 검사를 한 번 실행한다.

```sh
npm run check
```

- Frontend 또는 Backend 애플리케이션 코드
- 직접 의존성, build 또는 test 설정
- Hook, 검사 스크립트와 루트 검증 자동화
- Frontend와 Backend를 함께 변경한 작업
- 경로 영향 범위를 확실히 분류할 수 없는 작업

성공한 전체 검사는 이후 코드나 검증 설정이 결과를 무효화할 때만 반복한다. Markdown-only 변경은 Format, 상대 링크와 Git 추적 상태를 확인하고 전체 애플리케이션 검사를 생략할 수 있으며, 생략 사유를 PR에 남긴다.

## Hook 장애 처리

보호 명세, Patch lifecycle과 소유권 Guardrail은 `--no-verify`, 환경 override 또는 다른 Git
명령으로 우회하지 않는다. Hook 자체가 고장 나면 작업을 중단하고 `npm run check:precommit`
및 실패한 하위 명령을 직접 재현해 원인을 고친 뒤 정상 Hook으로 커밋한다.

## 참고

- [커밋 컨벤션](COMMIT_CONVENTION.md)
- [Husky 공식 문서](https://typicode.github.io/husky/)
