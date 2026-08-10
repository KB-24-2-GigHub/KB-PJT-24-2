# 프로젝트 관리 가이드

이 문서는 KB-PJT-24-2 레포지토리의 GitHub 기반 협업 규칙을 정의합니다.

## 기본 원칙

- 모든 작업은 이슈에서 시작합니다.
- 하나의 이슈는 하나의 명확한 결과물을 목표로 합니다.
- PR은 반드시 관련 이슈를 연결합니다.
- 프론트엔드와 백엔드는 하나의 레포지토리 안에서 `frontend/`, `backend/`로 나누어 관리합니다.
- React, Spring Boot, JPA는 사용하지 않습니다.

## 작업 흐름

| 단계        | 담당        | 기준                                                   |
| ----------- | ----------- | ------------------------------------------------------ |
| 이슈 생성   | 작업 제안자 | 작업 성격에 맞는 Issue Form의 필수 항목 작성           |
| 이슈 정리   | 팀          | 우선순위, 영역, Project 상태 지정                      |
| 브랜치 생성 | 담당자      | 이슈 번호가 포함된 브랜치명 사용                       |
| 구현        | 담당자      | 작은 커밋 단위로 진행                                  |
| PR 생성     | 담당자      | PR 템플릿 작성, 관련 이슈 연결                         |
| 리뷰        | 리뷰어      | 동작, 영향 범위, 테스트, 컨벤션 확인                   |
| 머지        | 팀          | 대상 브랜치의 보호·검증 정책 충족 후 머지              |
| 종료        | 통합 담당자 | 승인 브랜치 병합과 AC를 확인하고 카드 유형에 맞게 종료 |

## 이슈 작성 규칙

GitHub의 이슈 생성 화면에서 작업 성격에 맞는 Issue Form을 선택합니다. 각 Form의 입력 항목, 필수 여부와 기본 라벨 설정은 `.github/ISSUE_TEMPLATE/*.yml`이 실행 가능한 원본입니다.

| Form                                                       | 사용 시점                           | 핵심 입력                                |
| ---------------------------------------------------------- | ----------------------------------- | ---------------------------------------- |
| [기능 개발](../.github/ISSUE_TEMPLATE/feature_request.yml) | 새로운 사용자 기능, 화면, API       | 목표, AC, 비범위, 위험, 모듈, 검증, 선행 |
| [버그 리포트](../.github/ISSUE_TEMPLATE/bug_report.yml)    | 재현 가능한 오류나 기대와 다른 동작 | 수정 목표, 재현, AC, 비범위, 위험, 검증  |
| [작업 태스크](../.github/ISSUE_TEMPLATE/task.yml)          | 문서, 설정, 리팩터링, 테스트, 조사  | 목표, AC, 비범위, 위험, 모듈, 검증, 선행 |

이슈와 PR 제목의 기본 형식은 다음과 같습니다.

```text
<type>: [AREA] <summary>
```

Type과 Area Tag의 공통 목록, 커밋 메시지의 이슈 번호 표기는 [커밋 컨벤션](COMMIT_CONVENTION.md)을 따릅니다. 각 Issue Form은 작업 성격에 맞는 선택지만 제공합니다. 조사 작업은 작업 태스크에서 `chore`를 선택합니다.

Issue Form을 변경할 때는 필드 안내를 별도 문서에 복제하지 않습니다. 작업 흐름이나 제목 정책이 바뀌면 이 문서를, 공통 Type이나 Area가 바뀌면 커밋 컨벤션·검증 스크립트·관련 Issue Form을 같은 PR에서 함께 갱신합니다.

모든 카드는 `goal`, 3~7개의 `acceptance`, `non_goals`, `risk`, `primary_module`,
`affected_modules`, `verification`, `depends_on`을 한 화면에서 제공한다. API 영향이 있을 때만
`required_operations`를, DB 영향과 명시적 관리자 승인이 있을 때만 `migration_scope`를 작성한다.
문서 링크와 기존 Issue를 직접 연결하고, 같은 설명을 여러 필드에 반복하지 않는다.

Issue Form은 저장소의 기본 브랜치인 `main`에 반영된 뒤 GitHub 이슈 생성 화면에 나타납니다. `dev` 단계에서는 YAML 구문과 변경 내용을 검토하고, `main` 반영 후 실제 화면을 확인합니다.

## 라벨과 Project 필드

현재 Issue Form은 저장소에 실제로 존재하는 다음 Type 라벨만 자동 지정합니다.

| Form        | 자동 지정 라벨  |
| ----------- | --------------- |
| 기능 개발   | `type: feature` |
| 버그 리포트 | `type: bug`     |
| 작업 태스크 | `type: task`    |

`Status`, `Area`, `Priority`는 Issue 라벨이 아니라 GitHub Projects 필드로 관리합니다. 필드 값과 이동 기준은 [GitHub Projects 패널 운영 가이드](GITHUB_PROJECTS_PANEL_GUIDE.md)를 따릅니다.

## 위험과 리뷰 책임

| Risk | 예시                                 | 최소 검토 책임                      |
| ---- | ------------------------------------ | ----------------------------------- |
| `R0` | 문구, 비동작 메타데이터              | 작성자 검증                         |
| `R1` | 국소 문서, UI, 테스트                | 영향 영역 검토                      |
| `R2` | 일반 애플리케이션 동작과 구조        | 모듈 구현 및 통합 검토              |
| `R3` | 보안, 금액, Schema, 원자 Transaction | PM/Admin 범위 승인과 해당 전문 검토 |

- PM/Repository Administrator는 승인 통합 브랜치, 보호 SPEC·Migration 범위와 최종 프로그램
  통합 게이트를 소유한다.
- 구현자는 이슈 범위의 코드·문서·테스트, PR 증거와 잔여 위험을 소유한다.
- Backend, Frontend, DB, 보안·금액과 통합 검토는 실제 영향이 있는 역할만 요구한다. 같은
  관리자가 여러 역할을 겸할 수 있지만 승인 근거와 검증 결과는 PR에 남긴다.
- Guardrail은 금지 패턴·잠금·형식을 검사할 뿐 제품·Schema 의미 승인을 대신하지 않는다.
- Project 자동화는 상태를 반영할 뿐 완료 여부를 판정하지 않는다.

## 브랜치 전략

| 브랜치                   | 용도                                         |
| ------------------------ | -------------------------------------------- |
| `main`                   | 항상 배포 가능하거나 제출 가능한 기준 브랜치 |
| `dev`                    | 기능을 모아 공동 검증하는 통합 개발 브랜치   |
| `dev2`                   | 승인된 프로그램이 선언한 임시 통합 브랜치    |
| `feature/12-login-page`  | 기능 개발                                    |
| `fix/23-session-timeout` | 버그 수정                                    |
| `docs/5-project-guide`   | 문서 수정                                    |
| `chore/7-husky-setup`    | 설정, 빌드, 운영 작업                        |
| `hotfix/31-login-error`  | 배포된 `main`의 긴급 수정                    |

브랜치명은 소문자와 하이픈을 사용하고, 가능하면 이슈 번호를 포함합니다. 브랜치명에는 `#`를 넣지 않고 숫자만 사용합니다.

### 브랜치 흐름

| 작업                     | 분기 기준              | PR 대상               | 머지 후 처리                             |
| ------------------------ | ---------------------- | --------------------- | ---------------------------------------- |
| 기능·일반 버그·문서·설정 | 최신 승인 통합 브랜치  | 같은 승인 통합 브랜치 | 작업 브랜치 삭제                         |
| 프로그램 최종 통합       | 검증된 프로그램 브랜치 | `dev`                 | Parent·Milestone 종료 감사               |
| 배포·제출                | `dev`                  | `main`                | 배포·제출 검증                           |
| 배포 긴급 수정           | 최신 `main`            | `main`                | `main` 변경을 `dev`로 동기화하는 PR 생성 |

- 일반 작업의 기본 통합 브랜치는 `dev`입니다. 현재 이슈 또는 승인된 Parent 프로그램이
  `dev2` 같은 별도 통합 브랜치를 선언하면 그 값을 사용합니다.
- 이슈, Parent와 Native dependency의 통합 브랜치 선언이 충돌하거나 원격 기준 브랜치가 없으면
  작업을 시작하지 않고 담당자에게 보고합니다.
- `main`, `dev`와 프로그램별 통합 브랜치에는 직접 Push하지 않고 PR로만 반영합니다.
- 일반 작업 브랜치는 항상 최신 승인 통합 브랜치에서 만듭니다.
- 하나의 Issue에서 Frontend와 Backend가 함께 변경되면 별도 작업으로 나눌 이유가 없는 한 같은 작업 브랜치를 사용합니다.
- `dev` 검증이 끝난 배포 묶음은 `dev`에서 `main`으로 PR을 생성합니다.
- `hotfix/*`는 최신 `main`에서 만들고 `main`에 머지한 뒤, `main`에서 `dev`로 동기화 PR을 생성합니다.

### `dev` 최초 생성

팀 담당자 한 명이 최신 `main`에서 한 번만 생성하고 원격에 Push합니다.

```sh
git switch main
git pull --ff-only origin main
git switch -c dev
git push -u origin dev
```

원격 `dev`가 생성된 다음에는 GitHub에서 `main`과 `dev`의 직접 Push를 제한하고 PR 승인과 필수 검사를 적용합니다.

다른 팀원이 로컬 `dev`를 처음 받을 때는 다음과 같이 실행합니다.

```sh
git fetch origin
git switch --track origin/dev
```

### 프로그램 통합 브랜치 생성

이슈 또는 승인된 Parent 프로그램이 별도 통합 브랜치를 선언한 경우에만 최신 `dev`에서 한 번
생성합니다. 예를 들어 `dev2`는 다음 기준으로 만들고, 원격 생성 직후 프로그램이 승인한
브랜치 보호와 검증 정책을 적용합니다.

```sh
git fetch origin
git switch dev
git pull --ff-only origin dev
git switch -c dev2
git push -u origin dev2
```

프로그램 작업 브랜치는 최신 `dev2`에서 만들고 PR도 `dev2`를 대상으로 합니다. 프로그램의
최종 통합 이슈가 완료될 때만 검증된 `dev2`를 `dev`로 합칩니다.

Refactoring Milestone #8의 `dev2`는 PM이 단독으로 운영하는 임시 통합 브랜치입니다. 모든
변경은 PR로 병합하고 관리자 보호, 대화 해결, force-push 및 브랜치 삭제 금지를 유지하지만,
필수 승인 인원은 0명으로 설정해 PM이 이슈 검증을 확인한 뒤 직접 병합할 수 있습니다. 이
예외는 `dev`와 `main`의 승인 정책을 변경하지 않습니다.

### 프로그램 종료 의미

```text
latest dev → dev2 → issue branch → dev2 → audited dev2 → dev → release → main
```

| 사실                                               | 종료 대상                   | 처리                       |
| -------------------------------------------------- | --------------------------- | -------------------------- |
| 서브 이슈 PR이 `dev2`에 병합되고 AC·필수 검증 충족 | 해당 서브 이슈              | 수동 Close, Project `Done` |
| 최종 `dev2 → dev` PR이 통합 감사 후 병합           | Parent와 프로그램 Milestone | 수동 Close, Project `Done` |
| 검증된 `dev → main` PR 병합                        | 별도 Release item           | Release 사실 기록          |

`dev2`와 `dev`가 기본 브랜치가 아니면 `Closes` 자동화에 의존하지 않는다. 통합 담당자가 실제
base, merge SHA, AC와 검증을 확인한 뒤 이슈를 닫는다. `dev2` 또는 `dev` 통합 완료를 `main`
Release 완료로 표현하지 않는다. Issue Form과 PR template은 기본 브랜치 `main`에 반영된 뒤
GitHub 생성 화면에 활성화되므로, 소스 통합과 UI Release도 구분한다.

### 작업 브랜치 생성

```sh
git switch dev
git pull --ff-only origin dev
git switch -c feature/12-login-page
git push -u origin feature/12-login-page
```

별도 통합 브랜치가 선언된 프로그램에서는 위 예시의 `dev`를 승인된 브랜치로 바꿉니다.

## 코드와 Migration의 동일 PR 정책

동일 PR은 코드와 Schema 호환성을 하나의 리뷰 단위에서 확인한다는 뜻이며 배포 시 하나의 DB
Transaction으로 적용한다는 뜻이 아니다.

| 조건                                                            | 코드와 동일 PR                                                               |
| --------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| 기능 요구가 Schema 변경을 암시할 뿐 승인 범위가 없음            | 금지                                                                         |
| 이슈에 정확한 table·invariant·신규 Migration·검증 범위가 없음   | 금지                                                                         |
| 적용된 기존 Migration 수정·삭제·checksum 변경                   | 항상 금지                                                                    |
| 현재 작업자에게 명시된 PM/Admin 승인과 `migration_scope`가 있음 | 신규 immutable Flyway, 파생 Schema, Mapper/Service와 테스트를 원자 검토 가능 |
| 보호 SPEC의 `accepted` 전환과 일반 구현·Migration 혼합          | 금지                                                                         |
| 공유·Staging·Production DB 적용                                 | 별도 명시 지시 없으면 금지                                                   |

Migration PR은 DB owner/reviewer, 통합 DDL·Schema 문서, 호환 코드와 관련 테스트를 함께
검토한다. 검증은 폐기 가능한 DB 또는 명시적으로 허용된 로컬 DB에서만 수행하고
[`runbooks/DATABASE_RUNBOOK.md`](runbooks/DATABASE_RUNBOOK.md)의 Flyway 순서와 복구 규칙을
따른다. draft Patch는 제품 계약 변경분일 뿐 Migration 권한의 근거가 아니다.

## PR 규칙

- PR 제목은 커밋 컨벤션과 비슷하게 작성합니다.
- 일반 작업 PR은 승인된 통합 브랜치를 대상으로 만들고 본문에 `Refs #이슈번호`를 적습니다. 별도 선언이 없으면 대상은 `dev`입니다. 이 표기는 이슈를 교차 참조하지만 종료하지 않습니다.
- 작업 통합 브랜치 대상 PR은 우측 `Development`에서 관련 이슈를 수동 연결해 작업 중인 PR로 표시합니다.
- 프로그램 서브 이슈는 승인 프로그램 브랜치 병합 후 AC를 확인해 수동 종료하고, Parent와 Milestone은 최종 프로그램 브랜치 → `dev` 통합 후 종료합니다.
- 배포·제출 PR은 `dev`에서 `main`을 대상으로 만들고 Release item만 종료합니다. 프로그램 통합 완료와 같은 의미로 취급하지 않습니다.
- 긴급 수정 PR은 `hotfix/*`에서 `main`을 대상으로 만들고, 머지 후 `main`에서 `dev`로 동기화합니다.
- 변경 범위가 넓다면 기능별로 PR을 나눕니다.
- 리뷰어가 재현할 수 있도록 확인 방법을 적습니다.
- 화면 변경은 스크린샷 또는 짧은 설명을 남깁니다.

## 완료 기준

작업은 카드 유형에 맞는 승인 통합 브랜치에서 다음 조건을 충족해야 `Done`으로 이동할 수 있습니다.

- 이슈의 완료 조건을 충족했습니다.
- 관련 PR이 대상 브랜치의 보호·검증 정책을 충족하고 머지되었습니다.
- 필요한 테스트 또는 수동 검증 결과가 PR에 남아 있습니다.
- Project 패널 상태가 최신입니다.
- 금지 기술인 React, Spring Boot, JPA가 추가되지 않았습니다.

서브 이슈의 `Done`은 `dev2` 같은 승인 프로그램 브랜치 통합, Parent/Milestone의 `Done`은
최종 `dev` 통합, Release item의 `Done`은 `main` 반영을 뜻한다. 동일한 Project 상태명을
사용해도 이 세 사실을 서로 대신하거나 과장하지 않는다.
