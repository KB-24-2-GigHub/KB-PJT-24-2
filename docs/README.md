# 개발 문서 색인

이 문서는 사람과 저장소 에이전트가 필요한 기준으로 이동하는 단일 문서 라우터입니다.
현재 구현 목록을 복제하지 않고, 규범 문서와 실행 가능한 근거를 분리해 안내합니다.

## 신뢰 기준

| 확인하려는 내용              | 우선 확인할 근거                                     |
| ---------------------------- | ---------------------------------------------------- |
| 승인된 제품 동작과 수용 기준 | `docs/specs/`의 보호 명세                            |
| 현재 구현 동작과 구현 여부   | 코드, 설정, 집중 테스트, 실행 결과, Runtime Swagger  |
| 현재 DB 구조와 변경 순서     | Flyway Migration                                     |
| 기술 선택과 의존성 정책      | `DEPENDENCY_SPECIFICATION.md`와 실행 가능한 Manifest |
| 과거 결정과 이전 현황        | `docs/archive/`                                      |

코드가 보호 명세와 다르면 일반 구현 에이전트는 명세를 고치지 않습니다. 명세대로 코드를
수정하거나, 계약 변경이 필요하면 프로젝트 매니저·저장소 관리자에게 충돌을 보고합니다.
현재 Endpoint·Route·Mock·기능 완료 목록은 중앙 문서로 관리하지 않으며 작업할 때 코드에서
다시 확인합니다.

## 에이전트 진입점

이슈 작업은 공유 계약을 읽은 뒤 현재 Issue, Parent, 직접 Native dependency, 위험도, 승인 기록과
대상 통합 브랜치를 먼저 확인합니다. 그 다음 현재 Issue에 필요한 보호 계약과 `draft` Patch,
Architecture, 세부 문서 순으로 이동하며 같은 Milestone의 무관한 이슈를 미리 읽지 않습니다.

| 작업               | 먼저 읽을 문서                                                     | 역할                                               |
| ------------------ | ------------------------------------------------------------------ | -------------------------------------------------- |
| 모든 저장소 작업   | [`agent/PROJECT_RULES.md`](agent/PROJECT_RULES.md)                 | 공통 기술·소유권·검증 계약                         |
| 기능 구현 시작     | [`agent/ARCHITECTURE_OVERVIEW.md`](agent/ARCHITECTURE_OVERVIEW.md) | 런타임 구조와 책임 경계                            |
| 모듈·테이블 경계   | [`agent/MODULE_BOUNDARIES.md`](agent/MODULE_BOUNDARIES.md)         | 논리 모듈, Write owner, 공개 Service와 Tx 계약     |
| Java 모델링·정리   | [`agent/JAVA_MODELING_GUIDE.md`](agent/JAVA_MODELING_GUIDE.md)     | Lombok, 타입, interface, Exception, 복잡성 기준    |
| 위험 기반 검증     | [`agent/VERIFICATION_GUIDE.md`](agent/VERIFICATION_GUIDE.md)       | R0~R3, Characterization, 장기 생명주기와 실행 명령 |
| 코드 진입점 탐색   | [`agent/IMPLEMENTATION_GUIDE.md`](agent/IMPLEMENTATION_GUIDE.md)   | Route, API, Backend, DB 작업별 안정적인 탐색 순서  |
| 최상위 제품 목표 확인 | [`specs/MVP_SCOPE.md`](specs/MVP_SCOPE.md)                       | 원본 시연 시나리오, Priority와 화면 성공 판정      |
| 요구사항 확인      | [`specs/REQUIREMENTS.md`](specs/REQUIREMENTS.md)                   | 승인된 요구사항과 수용 기준                        |
| API 계약 확인      | [`specs/API_SPEC.md`](specs/API_SPEC.md)                           | 승인된 REST 계약                                   |
| 제품 결정 확인     | [`specs/DECISIONS.md`](specs/DECISIONS.md)                         | 승인·미결정·폐기 결정                              |
| 요구사항 연결 확인 | [`specs/SPEC_TRACEABILITY.md`](specs/SPEC_TRACEABILITY.md)         | 요구사항과 API·DB 도메인의 안정적인 연결           |
| DB 구조 확인       | [`agent/SCHEMA_OVERVIEW.md`](agent/SCHEMA_OVERVIEW.md)             | Migration과 핵심 불변식 요약                       |
| DB 운영·검증       | [`runbooks/DATABASE_RUNBOOK.md`](runbooks/DATABASE_RUNBOOK.md)     | Compose, Flyway, Snapshot 검증 절차                |
| 계약서 보존 파기 운영 | [`runbooks/CONTRACT_RETENTION_RUNBOOK.md`](runbooks/CONTRACT_RETENTION_RUNBOOK.md) | Dry-run 결과 확인, 재처리, 데모 시연 |
| 전체 DB 관계 확인  | [`DATABASE_SCHEMA_ERD.md`](DATABASE_SCHEMA_ERD.md)                 | 전체·기능별 ERD와 제약                             |
| 의존성·빌드 변경   | [`DEPENDENCY_SPECIFICATION.md`](DEPENDENCY_SPECIFICATION.md)       | 허용 기술, 버전, 변경 절차                         |

공유 계약은 새 대화, 에이전트 인계, 브랜치 전환 또는 계약 변경 후 다시 읽습니다. 같은
대화에서 바뀌지 않았다면 반복해서 읽지 않습니다. 상세 명세와 Runbook은 현재 작업에 필요한
부분만 선택합니다.

제품 범위, Priority, 시연 성공 결과 또는 `P0`/`P1`/`P2`/`Deferred`를 판단하는 작업은
[`specs/MVP_SCOPE.md`](specs/MVP_SCOPE.md)를 먼저 완전히 읽고, 그다음 관련 요구사항·API·결정·추적
문서에서 안정적인 ID와 공식 계약을 확인합니다.

## 보호 명세

[`specs/README.md`](specs/README.md)는 명세 릴리스, 소유권과 변경 절차의 기준입니다.

- `docs/specs/**`는 프로젝트 매니저·저장소 관리자가 승인하는 제품 계약입니다.
- 일반 구현 에이전트는 읽기만 합니다.
- 명시적으로 범위가 정해진 관리자 명세 릴리스에서만 현재 개인 에이전트가 수정할 수 있습니다.
- `SPEC_LOCK.json`의 정규화 SHA-256과 프로젝트 Guardrail이 우발적인 변경·누락을 검사합니다.
- `.github/CODEOWNERS`의 실제 Merge 강제는 GitHub Branch Ruleset에서 Code Owner 승인을
  필수로 설정해야 합니다.

현재 구현 상태는 명세에 기록하지 않습니다. Runtime Swagger는 구현 탐색과 대조에 사용하지만,
보호 명세에 없는 계약을 새 제품 규약으로 만들지는 않습니다.

## 데이터베이스

| 작업                     | 기준                                                                                         |
| ------------------------ | -------------------------------------------------------------------------------------------- |
| 순차 업그레이드          | [`../backend/src/main/resources/db/migration/`](../backend/src/main/resources/db/migration/) |
| 로컬 실행·검증·장애 대응 | [`runbooks/DATABASE_RUNBOOK.md`](runbooks/DATABASE_RUNBOOK.md)                               |
| 핵심 테이블·제약 요약    | [`agent/SCHEMA_OVERVIEW.md`](agent/SCHEMA_OVERVIEW.md)                                       |
| 전체 관계·기능별 ERD     | [`DATABASE_SCHEMA_ERD.md`](DATABASE_SCHEMA_ERD.md)                                           |
| 새 빈 DB 참고용 통합 DDL | [`database/schema-snapshot-202608121403.sql`](database/schema-snapshot-202608121403.sql)     |

Flyway Migration이 DB 스키마의 단일 원본입니다. 통합 DDL은 표시된 Flyway Head를 새 빈 DB에
재현하기 위한 참고 산출물이며 기존 DB 업그레이드에 사용하지 않습니다. Migration과
`docs/database/**`는 프로젝트 매니저·저장소 관리자 관리 영역이고, 명시적으로 범위가 정해진
관리 작업에서만 개인 에이전트가 변경합니다.

## 애플리케이션과 협업 문서

| 영역                      | 문서                                                               |
| ------------------------- | ------------------------------------------------------------------ |
| Frontend 설치·실행·검증   | [`../frontend/README.md`](../frontend/README.md)                   |
| Backend WAR·DB 연결·검증  | [`../backend/README.md`](../backend/README.md)                     |
| 최초 환경 준비            | [`GETTING_STARTED.md`](GETTING_STARTED.md)                         |
| LLM 분쟁 DEMO 실행·복구   | [`runbooks/DISPUTE_DEMO_RUNBOOK.md`](runbooks/DISPUTE_DEMO_RUNBOOK.md) |
| Issue·Branch·PR·종료      | [`PROJECT_MANAGEMENT_GUIDE.md`](PROJECT_MANAGEMENT_GUIDE.md)       |
| Commit 규약               | [`COMMIT_CONVENTION.md`](COMMIT_CONVENTION.md)                     |
| Git Hook·Guardrail 책임   | [`GIT_HOOKS_HUSKY_GUIDE.md`](GIT_HOOKS_HUSKY_GUIDE.md)             |
| GitHub Projects 상태 의미 | [`GITHUB_PROJECTS_PANEL_GUIDE.md`](GITHUB_PROJECTS_PANEL_GUIDE.md) |
| Claude Code 어댑터        | [`../CLAUDE.md`](../CLAUDE.md)                                     |

루트 `AGENTS.md`, `docs/memory/`, `docs/reports/`, `NOTICE.md`는 현재 Codex 사용자의 개인 운영
파일입니다. 플러그인, 권한, 모델과 개인 설정도 공유 계약에 넣지 않습니다.
Git에서 무시되는 로컬 참고 문서도 공유 원본이 아니며 이 Router에서 링크하지 않습니다.

## 아카이브

[문서 아카이브 정책과 목록](archive/README.md)을 따릅니다. 아카이브 문서는 과거 근거이며 현재
계약이나 구현 상태로 사용하지 않습니다.
