# 시작 가이드

이 문서는 새 clone에서 공통 개발 환경을 준비하는 최초 1회 절차만 설명한다. 일상적인 Issue·브랜치·커밋·PR 규칙은 각 기준 문서에서 확인한다.

## 사전 조건

- Git
- Java 17
- Node.js `>=20.19.0 <25`
- npm `>=11 <12`

정확한 기술 경계와 버전 원본은 [`DEPENDENCY_SPECIFICATION.md`](DEPENDENCY_SPECIFICATION.md)를 확인한다. Docker, MySQL과 외부 Tomcat은 해당 작업을 시작할 때 애플리케이션 안내와 DB Runbook에 따라 준비한다.

## 1. 저장소 받기

```sh
git clone https://github.com/Flamingo7562/KB-PJT-24-2.git
cd KB-PJT-24-2
```

일반 개발의 기본 통합 기준은 `dev`다. 작업 브랜치를 만들기 전 현재 Issue, Parent와 직접
Native dependency가 별도 통합 브랜치를 선언하는지 확인하고,
[`PROJECT_MANAGEMENT_GUIDE.md`](PROJECT_MANAGEMENT_GUIDE.md)의 현재 브랜치 전략을 따른다.

## 2. 공통·Frontend 의존성 설치

루트와 Frontend는 서로 다른 manifest와 lockfile을 사용하므로 각각 설치한다.

```sh
npm ci
npm --prefix frontend ci
```

Windows PowerShell에서 `npm.ps1` 실행 정책 오류가 발생하면 `npm.cmd`를 사용한다.

```powershell
npm.cmd ci
npm.cmd --prefix frontend ci
```

개별 패키지를 임의로 설치하지 않는다. 직접 의존성을 변경해야 한다면 기술 스택과 의존성 기준의 같은 PR 갱신 절차를 따른다.

## 3. Git Hook과 커밋 Template 확인

루트 설치 과정에서 Husky `prepare`가 실행된다. Hook shim이 없으면 다음 명령으로 다시 생성한다.

```sh
npm run prepare
```

Husky 9의 Hook 경로를 확인한다.

```sh
git config --get core.hooksPath
```

정상 결과:

```text
.husky/_
```

커밋 Template을 이 저장소에만 적용한다.

```sh
git config commit.template .gitmessage.txt
git config --get commit.template
```

정상 결과:

```text
.gitmessage.txt
```

Hook 동작과 문제 해결은 [`GIT_HOOKS_HUSKY_GUIDE.md`](GIT_HOOKS_HUSKY_GUIDE.md), 메시지 형식은 [`COMMIT_CONVENTION.md`](COMMIT_CONVENTION.md)를 확인한다.

## 4. 영역별 로컬 설정

현재 작업에 필요한 영역만 준비한다.

| 작업                     | 안내                                                           |
| ------------------------ | -------------------------------------------------------------- |
| Frontend 실행·환경변수   | [`../frontend/README.md`](../frontend/README.md)               |
| Backend 빌드·Tomcat 설정 | [`../backend/README.md`](../backend/README.md)                 |
| Docker MySQL·Flyway      | [`runbooks/DATABASE_RUNBOOK.md`](runbooks/DATABASE_RUNBOOK.md) |

환경별 설정, 비밀번호와 로컬 절대 경로는 Git에 커밋하지 않는다.

## 5. 공통 검사

도구 설치와 Java·Frontend·Backend 검사 진입점이 정상인지 확인한다.

```sh
npm run check
```

Windows에서는 다음 명령을 사용할 수 있다.

```powershell
npm.cmd run check
```

전체 검사는 Guardrail, 하네스 테스트, Frontend ESLint와 Backend Gradle `check`를 실행한다. Frontend build·Vitest와 Backend WAR처럼 작업별로 필요한 검증은 각 애플리케이션 README와 Lint·Hook 기준을 따른다.

## 다음 작업

- Issue·브랜치·PR: [`PROJECT_MANAGEMENT_GUIDE.md`](PROJECT_MANAGEMENT_GUIDE.md)
- 커밋 메시지: [`COMMIT_CONVENTION.md`](COMMIT_CONVENTION.md)
- Lint·Guardrail·Hook: [`GIT_HOOKS_HUSKY_GUIDE.md`](GIT_HOOKS_HUSKY_GUIDE.md)
- 전체 문서 라우터: [`README.md`](README.md)
