# 제품 명세 안내

| 항목             | 값                         |
| ---------------- | -------------------------- |
| 명세 릴리스      | `6.0.0`                    |
| 승인일           | 2026-08-10                 |
| 소유자           | PM/Admin Master            |
| 최소 호환 스키마 | Flyway `202608061428` 이상 |

이 디렉터리는 Gig Hub의 제품 요구와 외부 REST 계약을 보관하는 규범 문서 영역입니다.
구현 코드, Swagger 화면, 작업 이력은 이 문서의 근거가 될 수 있지만 이 문서를 자동으로
바꾸는 권한은 갖지 않습니다.

최소 호환 스키마는 이 명세가 요구하는 최초 DB 구조를 뜻하며 현재 Flyway Head나 구현
진행률을 뜻하지 않습니다. 이후 Migration이 제품 의미나 외부 계약을 바꾸지 않고 호환성을
유지한다면 이 명세 릴리스를 갱신하지 않습니다.

`6.0.0`은 PM/Admin이 제공한 외부 `MVP_SCOPE.md`의 37개 P0를 최상위 제품 목표로 공식화한
Major 릴리스입니다. REQUIREMENTS에 모든 P0의 역할·선행 상태·행동·성공·실패를 고정하고,
API·결정·기능 이슈·현재 구현 상태와 연결했습니다. 목표 계약을 추가한 사실은 구현 완료를
뜻하지 않으며 M4~M7 공백은 이번 문서 행정 릴리스에서 코드로 채우지 않습니다. 외부 원본은
입력 자료로 보존하고 저장소의 공식 계약은 이 디렉터리의 다섯 잠금 문서입니다.

`5.0.0`은 `dev`에서 구현 계약으로 사용한 `SPEC-161-01`을 정식 명세에 통합한 Major
릴리스입니다. 사업장 위치 확정 책임, READY·NO_SHOW·CHECK_OUT_MISSING 시각과 Scheduler,
출퇴근 스캔의 위치·멱등·동시성·오류, 조기·정상 퇴근의
`SCHEDULED/due_at=recordedAt+24시간`, WORKER 응답과 Capability 기반 지원 브라우저를
확정했습니다. 기존 스캔 Operation에 필수 `Idempotency-Key`, `accuracyMeters`, `capturedAt`을
추가하므로 외부 요청 계약은 하위 호환되지 않습니다. QR 재발급은 Header 없이 GET 복구를
사용하며 이번 릴리스에는 Scheduler Index, 수동 보정 Column 또는 다른 Migration을 추가하지
않습니다. 최소 호환선은
[Issue #221](https://github.com/Flamingo7562/KB-PJT-24-2/issues/221)에서 구현하고
[PR #231](https://github.com/Flamingo7562/KB-PJT-24-2/pull/231)로 병합한
`V202608061428__add_document_access_audit_details.sql`입니다. 이 Migration보다 앞선
스키마는 계약 문서 Version과 파일 접근 거부 사유를 포함한 감사 계약을 충족하지 않습니다.

## 문서 라우팅

| 알고 싶은 내용                          | 문서                                         |
| --------------------------------------- | -------------------------------------------- |
| 제품이 제공해야 하는 기능과 수용 기준   | [REQUIREMENTS.md](REQUIREMENTS.md)           |
| 승인된 REST 경로, 요청, 응답, 오류 계약 | [API_SPEC.md](API_SPEC.md)                   |
| 승인·미결·폐기된 제품 결정              | [DECISIONS.md](DECISIONS.md)                 |
| 요구사항과 API·도메인의 연결            | [SPEC_TRACEABILITY.md](SPEC_TRACEABILITY.md) |

과거 개발 현황 중심 라우팅 표는
[2026-07-31 아카이브](../archive/specs/DEVELOPMENT_ROUTING_TABLE-2026-07-31.md)로
이동했습니다. 아카이브는 규범 계약이 아닙니다.

## 권위와 충돌 해결

제품 의미와 외부 계약의 우선순위는 다음과 같습니다.

1. [REQUIREMENTS.md](REQUIREMENTS.md)의 `MVP P0 시연 계약`, 승인된 요구사항과 수용 기준
2. [API_SPEC.md](API_SPEC.md)의 승인된 REST 계약
3. [DECISIONS.md](DECISIONS.md)의 승인 결정
4. [SPEC_TRACEABILITY.md](SPEC_TRACEABILITY.md)의 연결 정보

동일 순위에서 문장이 충돌하면 더 최근에 승인된 명세 릴리스를 따릅니다. 승인된 통합
브랜치의 기능 개발에서는 이 정식 SPEC에 현재 Issue와 직접 관련된 `draft` Patch만 더해 임시 계약으로
사용합니다. 관련 `draft`가 명시한 변경분은 해당 기능 범위에서 우선하지만, 다른 Patch까지
합성해 새 계약을 추론하지 않습니다. 명세와 코드 또는 Swagger가 충돌하면 코드를 기준으로
명세를 고치지 않고 [명세 Patch](../spec-patches/README.md)에 변경분을 기록합니다. DB의 물리 구조는
해당 릴리스가 선언한 Flyway 호환 기준을 따르며, DDL과 Migration의 변경 권한은 별도 소유자
정책을 따릅니다.

## 편집 권한

- Controller인 PM/Repository Admin `Flamingo7562`만 이 디렉터리의 규범 문서를
  승인·편집·배포합니다.
- 개발자와 구현 Agent는 이 디렉터리를 읽기 전용으로 취급합니다.
- 구현 중 계약의 누락, 모순, 변경 필요성을 발견하면 코드를 기준으로 명세를 직접 고치지
  않고 [명세 Patch 작성 절차](../spec-patches/README.md)에 따라 관련 `draft`를 작성합니다.
- 승인되지 않은 추정값, 구현 편의를 위한 필드, 임시 Endpoint를 규범 계약에 추가하지
  않습니다.
- 진행률, 임시 데이터 사용 여부, 구현 세부, 테스트 결과, 작업 목록은 원칙적으로 이
  디렉터리에 기록하지 않습니다. 단, PM이 승인한 특정 기준점의 MVP P0 구현 감사는 목표
  계약과 완료 판정을 분리하기 위해 SPEC_TRACEABILITY에 명시적으로 기록할 수 있습니다.

## 변경 절차

1. 개발자는 최신 정식 SPEC 버전을 기준으로 독립적인 최소 기능마다 짧은
   [명세 Patch](../spec-patches/README.md)를 `draft`로 작성합니다.
2. `draft`에는 안정적인 대상, 추가·변경 사항과 검증 가능한 완료 조건만 기록하며, 관련 기능
   코드와 같은 PR로 승인된 통합 브랜치에 병합할 수 있습니다.
3. 개발팀은 정식 SPEC과 현재 기능의 관련 `draft`를 함께 구현 계약으로 사용합니다. 기능이
   바뀌거나 철회되면 코드·테스트와 `draft`를 함께 고칩니다.
4. Controller는 필요한 시점에 이슈가 승인한 통합 브랜치의 최신 원격 추적 ref와 대상 중복을 확인하고, Patch 변경분을
   영향받는 정식 문서에 편집 통합합니다. 정식 SPEC 버전·릴리스 기록, `SPEC_LOCK.json`,
   Patch의 `accepted` 전환과 아카이브 이동을 하나의 원자적 릴리스로 처리합니다.
5. `accepted`는 정식 SPEC 반영이 끝난 상태이며 보관 Patch는 수정하지 않습니다. 후속 변경과
   되돌림은 새 `draft`와 새 정식 SPEC 릴리스로 처리합니다.
6. 승인·운영 릴리스에는 관련 `draft`가 남아 있어서는 안 됩니다. Swagger는 정식 SPEC과
   수락된 구현 결과를 보여주는 실행 계약 화면으로 생성합니다.

정적 OpenAPI YAML을 별도의 규범 원본으로 병행 관리하지 않습니다. 사람이 검토하는 계약의
원본은 이 디렉터리의 Markdown이며, Swagger/OpenAPI는 승인 계약을 코드에 반영한 결과를
검증하는 용도입니다.

## 릴리스 기록

| Version | 승인일     | 요약                                                                                                                                                                                                               |
| ------- | ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `6.0.0` | 2026-08-10 | 외부 MVP 목표의 37개 P0를 요구·API·결정·기능 이슈·현재 구현 상태와 연결하고 장기 Work 생명주기와 기능 공백을 분리한 Major 릴리스 ([#282](https://github.com/KB-24-2-GigHub/KB-PJT-24-2/issues/282), `SPEC-282-01`) |
| `5.0.0` | 2026-08-08 | M5 READY·노쇼·퇴근 누락, GPS 위치 확정, 스캔 멱등·오류, 정산 예약, 응답·지원 브라우저와 무 Migration 경계를 통합한 Major 릴리스 ([#161](https://github.com/Flamingo7562/KB-PJT-24-2/issues/161), `SPEC-161-01`)    |
| `4.2.0` | 2026-08-07 | 은행 표시명 브랜드 접두사와 사업장 고정 QR 최초 발급·조회·재발급 계약을 통합한 Minor 릴리스 ([#255](https://github.com/Flamingo7562/KB-PJT-24-2/issues/255), `SPEC-151-01`, `SPEC-162-01`)                         |
| `4.1.0` | 2026-08-07 | 합성 Mock 계좌의 canonical 은행 코드와 화면 표시명을 5개에서 20개로 확장하고 기존 코드 호환성을 유지한 Minor 릴리스 ([#247](https://github.com/Flamingo7562/KB-PJT-24-2/issues/247))                               |
| `4.0.1` | 2026-08-06 | 제품 동작을 유지하면서 명세 Patch를 `draft` 개발 계약과 SPEC 반영 완료 `accepted`의 2상태 흐름으로 경량화한 Patch 릴리스 ([#236](https://github.com/Flamingo7562/KB-PJT-24-2/issues/236))                          |
| `4.0.0` | 2026-08-06 | M4 Bearer 초대·Work Case 응답·수락 Aggregate·전자동의·계약 파일 접근 감사를 확정한 Major 릴리스 ([#153](https://github.com/Flamingo7562/KB-PJT-24-2/issues/153))                                                   |
| `3.0.1` | 2026-08-05 | 제품 의미와 REST 계약을 유지하면서 명세 Patch 제안·Controller 통합 절차와 원자적 릴리스 기준을 확정한 Patch 릴리스 ([#207](https://github.com/Flamingo7562/KB-PJT-24-2/issues/207))                                |
| `3.0.0` | 2026-08-05 | 비귀속 Demo Mock 계좌, 충전 PIN 인증, PIN 없는 출금 입금, 금융 오류·거래 표시·멱등 Claim 계약을 확정한 Major 릴리스 ([#202](https://github.com/Flamingo7562/KB-PJT-24-2/issues/202))                               |
| `2.1.1` | 2026-08-05 | 아이디·이메일 정규화 결과의 필수·최대 길이 경계를 확정한 Patch 릴리스                                                                                                                                              |
| `2.1.0` | 2026-08-04 | 예상하지 못한 서버 오류의 공통 `500 INTERNAL_ERROR` 외부 계약을 승인한 Minor 릴리스                                                                                                                                |
| `2.0.0` | 2026-08-04 | M2 인증·입력·OWNER 온보딩 계약을 확정하고 `employer_profiles`를 제거한 Major 릴리스                                                                                                                                |
| `1.0.0` | 2026-07-31 | 팀 합의 사항을 제품 요구, REST 계약, 결정 기록, 추적표로 분리한 최초 규범 릴리스                                                                                                                                   |
