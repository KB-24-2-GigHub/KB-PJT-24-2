# 제품 명세 안내

| 항목             | 값                                        |
| ---------------- | ----------------------------------------- |
| 명세 릴리스      | `9.0.0`                                   |
| 승인일           | 2026-08-21                                                   |
| 소유자           | PM/Admin Master                                              |
| 최소 호환 스키마 | Flyway `202608201125` 이상 (현재 Head `202608201125`)             |

이 디렉터리는 Gig Hub의 제품 요구와 외부 REST 계약을 보관하는 규범 문서 영역입니다.
구현 코드, Swagger 화면, 작업 이력은 이 문서의 근거가 될 수 있지만 이 문서를 자동으로
바꾸는 권한은 갖지 않습니다.

최소 호환 스키마는 이 명세가 요구하는 최초 DB 구조를 뜻하며 현재 Flyway Head나 구현
진행률을 뜻하지 않습니다. 이후 Migration이 제품 의미나 외부 계약을 바꾸지 않고 호환성을
유지한다면 이 명세 릴리스를 갱신하지 않습니다.

`9.0.0`은 최신 `dev@2f7eb3f91e03d4f2eefa5669ab8325d6cf26d4fe`에 병합된 19개 명세
Patch를 정식 계약으로 통합한 Major 릴리스입니다. 사업장 등록·주소 수정은 클라이언트 좌표 대신
서버의 도로명주소 변환 결과를 원자 저장하고, 야간 근무는 다음 날 종료로 해석하되 16시간을
넘지 않습니다. 지각·조퇴 정산은 저장된 분 단위 계산 Snapshot과 10원 절사 공식으로 WORKER
지급액과 OWNER 차액 환불액을 확정하며, 노쇼·퇴근 누락은 별도 승인으로 전액 환불합니다.

비밀번호 변경의 Session 회전, 탈퇴, HTML/CSS 계약 PDF, LLM 분쟁 DEMO, 보건증 생성·수정
응답과 표시 파일명, 알림 안읽음 필터·전체 읽음, 뱃지 집계·초대/근무 상세 노출, 역할별 로고
이동과 근무 상태 표기도 함께 확정합니다. 사업장 등록 요청과 정산 금융 의미가 바뀌므로 Major로
발행하며 최소 호환선은 계산 Snapshot 제약을 담은
`V202608201125__add_settlement_calculation_snapshot.sql`입니다. 이번 행정 릴리스는 이미 병합된
코드·테스트·22개 immutable Migration을 감사해 수락하며 새 코드·Migration·DDL·Backfill을
추가하지 않습니다.

`8.1.0`은 `DEC-OPEN-NOTIFICATION-CONTRACT`를 해소하고 인앱 알림 계약을 정식화한 Minor
릴리스입니다. 알림 유형 6종과 수신자, 이벤트 식별자(`sourceType`+`sourceId`)와 이동
대상(`workCaseId`)의 분리, `(수신자, notiType, sourceType, sourceId)` 유일성 중복 방지, 서버가
만든 완성 문구, 목록·안읽음 개수·단건 읽음 Operation과 오류를 확정합니다. 전달은 목록 조회가
단독으로 성립하고 SSE 스트림은 재조회 신호만 나르는 보조 수단이며, 스트림이 실패해도 목록
조회와 읽음 처리는 그대로 동작합니다. **전체 읽음 처리는 이번 계약에 두지 않습니다.** 기존
Operation을 바꾸지 않고 알림 Operation만 추가하므로 Minor로 발행합니다. 최소 호환선은 알림
저장 불변식을 담은 `V202608162210__create_notifications.sql`이며, 이 Migration보다 앞선 스키마는
중복 방지와 읽음 상태 정합성을 충족하지 않습니다. 이번 행정 릴리스 자체에는 코드·Migration·
DDL·Backfill이 없습니다.

`8.0.0`은 Work Case 금액 원본을 OWNER가 입력한 `dailyWage`와
`work_cases.agreed_wage` 약정 일급으로 바로잡은 Major 릴리스입니다. 등록·수정·초대·계약·
예치·조회·정산은 같은 약정 일급 Snapshot을 사용하고 시급을 저장·역산하거나 시간·휴게조건으로
일급을 다시 계산하지 않습니다. `lateMinutes`는 근태 정보로만 유지하며 정상·지각 완료 근무는
약정 일급 전액을 WORKER에게 지급하고 OWNER 환불액은 0원입니다. NO_SHOW 전액 환불은
유지합니다. 시간 경과 확보 금액과 자동 지각 공제·분할 정산은 별도 제품 결정 전 현재 MVP에
없습니다. 필수 요청 필드를 `hourlyWage`에서 `dailyWage`로 바로잡아 외부 계약이 바뀌므로 Major로
발행하며 이번 행정 릴리스에는 코드·Migration·DDL·Backfill이 없습니다. Frontend의 기존 시간
경과 비례 표시는 [추적표](SPEC_TRACEABILITY.md)에 Partial 잔여 공백으로 기록합니다.

`7.0.1`은 WORKER 홈·근무 이력의 당시 저장 모델 경계와 SHARED 보건증 단건 상세 문맥을
명시한 보완 릴리스입니다. 시급 Snapshot 전에는 `hourlyWage`, `expectedDeductionAmount`,
`expectedPaymentAmount`를 반환하지 않고 일급 기반 `expectedNetAmount`를 유지합니다. SHARED
보건증 상세는 목록의 `workCaseId` Query가 필수이며 서버가 관계를 자동 선택하지 않습니다.
두 보완은 새 Migration이나 오류 Code를 추가하지 않으며 #165·#132의 구현·통합 완료를
의미하지 않습니다. 8.0.0이 시급 Snapshot 도입 전제 자체를 대체합니다.

`7.0.0`은 M6 정상 지급·자동 Scheduler·NO_SHOW 환불·임금분쟁 자금 경계와 M7 문서·신뢰
뱃지 계약을 함께 확정한 Major 릴리스입니다. 정상 CHECK_OUT 뒤 Work Case는 이미
`COMPLETED`이고 Settlement는
`SCHEDULED/due_at=recordedAt+24시간`이며, OWNER 승인과 Scheduler는 같은 원자 지급 실행기와
결정적 원장 Key로 경합합니다. 열린 분쟁은 정상 지급과 NO_SHOW 환불을 막고 정상 Settlement를
기존 `due_at`의 `ON_HOLD`로 보류하며 마지막 열린 분쟁이 닫히면 `SCHEDULED`로 재개합니다.
NO_SHOW는 OWNER의 별도 멱등 승인으로 `REFUNDED`에 종료합니다.

7.0.0 보호 명세 릴리스 자체에는 코드나 Migration이 없었습니다. #72가 OWNER 원자 지급을
정렬했고 #171의 immutable `V202608112307__add_settlement_retry_and_dispute_title.sql`이
`REFUNDED`, Scheduler 재시도 필드, `disputes.title`과 생명주기 제약을 반영했습니다. 이후
#292 호환 제약까지 적용한 현재 Head는 `202608121403`입니다. 범용 Claim과
`(status,due_at)` Index는 계속 재사용합니다. #172·#174·#175 및 화면·통합 검증 이슈가
완료되기 전에는 M6 전체 목표 계약을 현재 구현 완료로 판정하지 않습니다.

문서 계약은 목록과 단건 상세의 안전한 Item·허용 Version, 파일 404 비식별·Checksum·감사,
보건증 새 문서 등록·발급일 수정·논리 삭제, Work Case 단위 공유, 계약서 3년 보존 뒤 Object
폐기를 확정합니다. 신뢰 뱃지는 최근 구간 대신 누적 10·20·30건과 정상 비율 80·90·100%를
사용하고 조회마다 원천 이력을 재계산합니다. 외부 동작은 기존 구조로 표현 가능했고 #179는
코드성 감사·뱃지 값 집합만 Flyway `202608111743`·`202608111744`로 보강했습니다.
#132의 안전 조회·감사는 완료됐지만 #131·#180~#183이 열려 있는 동안 나머지 문서·뱃지 기능은
Planned 또는 Partial이며 정식 명세와 Schema 승인을 구현 완료로 판정하지 않습니다.

`6.0.1`은 PM/Admin이 제공한 원본 [MVP_SCOPE.md](MVP_SCOPE.md)를 내용 변경 없이 저장소에
보존하고 에이전트 문서 진입점에 연결한 Patch 릴리스입니다. 이 원본은 제품 범위, Priority와
화면에서 판정하는 시연 성공 결과의 최상위 입력입니다. REQUIREMENTS·API_SPEC·DECISIONS·
SPEC_TRACEABILITY는 원본을 안정적인 ID, 공식 API, 결정과 구현 감사에 연결하는 파생 규범
계약입니다. 원본을 추적한 사실은 P0/P1/P2를 바꾸거나 기능 구현을 완료했다는 뜻이 아닙니다.

`6.0.0`은 원본의 37개 P0를 최상위 제품 목표로 공식화한 Major 릴리스입니다. REQUIREMENTS에
모든 P0의 역할·선행 상태·행동·성공·실패를 고정하고, API·결정·기능 이슈·현재 구현 상태와
연결했습니다. 목표 계약을 추가한 사실은 구현 완료를 뜻하지 않으며 M4~M7 공백은 해당 기능
이슈에서만 구현합니다.

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
| 최상위 제품 범위·Priority·시연 성공 판정 | [MVP_SCOPE.md](MVP_SCOPE.md)                 |
| 제품이 제공해야 하는 기능과 수용 기준   | [REQUIREMENTS.md](REQUIREMENTS.md)           |
| 승인된 REST 경로, 요청, 응답, 오류 계약 | [API_SPEC.md](API_SPEC.md)                   |
| 승인·미결·폐기된 제품 결정              | [DECISIONS.md](DECISIONS.md)                 |
| 요구사항과 API·도메인의 연결            | [SPEC_TRACEABILITY.md](SPEC_TRACEABILITY.md) |

과거 개발 현황 중심 라우팅 표는
[2026-07-31 아카이브](../archive/specs/DEVELOPMENT_ROUTING_TABLE-2026-07-31.md)로
이동했습니다. 아카이브는 규범 계약이 아닙니다.

## 권위와 충돌 해결

제품 의미와 외부 계약의 우선순위는 다음과 같습니다.

1. [MVP_SCOPE.md](MVP_SCOPE.md)의 제품 범위, Priority와 화면 성공 결과
2. [REQUIREMENTS.md](REQUIREMENTS.md)의 안정적인 요구사항 ID와 수용 기준
3. [API_SPEC.md](API_SPEC.md)의 승인된 REST 계약
4. [DECISIONS.md](DECISIONS.md)의 승인 결정
5. [SPEC_TRACEABILITY.md](SPEC_TRACEABILITY.md)의 연결 정보

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
| `9.0.0` | 2026-08-21 | 최신 `dev`의 탈퇴·비밀번호 Session, 서버 주소 변환, 야간 근무, 계약 PDF, LLM 분쟁 DEMO, 문서 응답, 알림 필터·전체 읽음, 뱃지·표시, 비례 정산과 퇴근 누락 환불을 통합한 Major 릴리스 ([#488](https://github.com/KB-24-2-GigHub/KB-PJT-24-2/issues/488), `SPEC-168-01`, `SPEC-180-01`, `SPEC-187-01`, `SPEC-188-01`, `SPEC-343-01`, `SPEC-349-01`, `SPEC-359-01`, `SPEC-375-01`, `SPEC-387-01`, `SPEC-413-01`, `SPEC-414-01`, `SPEC-423-01`, `SPEC-424-01`, `SPEC-432-01~02`, `SPEC-461-01`, `SPEC-470-01`, `SPEC-472-01`, `SPEC-484-01`) |
| `8.1.0` | 2026-08-17 | `DEC-OPEN-NOTIFICATION-CONTRACT`를 해소하고 알림 유형 6종·이벤트 식별자와 이동 대상 분리·중복 유일성·목록/안읽음/단건 읽음 Operation과 보조 SSE 전달을 확정한 Minor 릴리스 ([#382](https://github.com/KB-24-2-GigHub/KB-PJT-24-2/issues/382), `SPEC-382-01`, `SPEC-383-01`, `SPEC-384-01`, `SPEC-386-01`) |
| `8.0.0` | 2026-08-12 | 시급 입력·산정과 자동 지각 공제·분할 정산을 현재 MVP에서 제거하고 `dailyWage`·`agreed_wage` 약정 일급 및 정상·지각 전액 지급으로 정정한 Major 릴리스 ([#328](https://github.com/KB-24-2-GigHub/KB-PJT-24-2/issues/328), `SPEC-328-01`) |
| `7.0.1` | 2026-08-12 | WORKER 홈·이력에서 시급 Snapshot 전 세 공제 필드를 제외하고 SHARED 보건증 상세에 목록 `workCaseId` 문맥을 필수화한 보완 릴리스 ([#165](https://github.com/KB-24-2-GigHub/KB-PJT-24-2/issues/165), [#178](https://github.com/KB-24-2-GigHub/KB-PJT-24-2/issues/178), `SPEC-165-01`, `SPEC-178-07`) |
| `7.0.0` | 2026-08-12 | M6 Settlement 생명주기·경합·분쟁 보류와 M7 문서 목록·상세·보건증·공유·감사·보존·누적 신뢰 뱃지를 함께 확정한 Major 릴리스 ([#170](https://github.com/KB-24-2-GigHub/KB-PJT-24-2/issues/170), [#178](https://github.com/KB-24-2-GigHub/KB-PJT-24-2/issues/178), `SPEC-170-01`, `SPEC-178-01~06`) |
| `6.0.1` | 2026-08-11 | 원본 [MVP_SCOPE.md](MVP_SCOPE.md)를 내용 변경 없이 추적하고 에이전트 라우팅·파생 계약 관계·보호 Lock을 보완한 Patch 릴리스 ([#307](https://github.com/KB-24-2-GigHub/KB-PJT-24-2/issues/307), `SPEC-307-01`) |
| `6.0.0` | 2026-08-10 | 원본 MVP 목표의 37개 P0를 요구·API·결정·기능 이슈·현재 구현 상태와 연결하고 장기 Work 생명주기와 기능 공백을 분리한 Major 릴리스 ([#282](https://github.com/KB-24-2-GigHub/KB-PJT-24-2/issues/282), `SPEC-282-01`) |
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
