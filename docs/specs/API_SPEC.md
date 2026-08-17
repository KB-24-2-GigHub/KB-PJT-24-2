# REST API 명세

| 항목        | 값              |
| ----------- | --------------- |
| 명세 릴리스 | `8.1.0`         |
| 승인일      | 2026-08-17      |
| 소유자      | PM/Admin Master |
| Base Path   | `/api`          |

이 문서는 승인된 외부 REST 계약만 정의합니다. 제품 의미는
[REQUIREMENTS.md](REQUIREMENTS.md), 미결 계약은 [DECISIONS.md](DECISIONS.md)를 따릅니다.
문서에 없는 필드, 상태, Endpoint를 구현 편의로 추정하지 않습니다.

## MVP P0 Operation 계약

아래 표는 원본 [MVP_SCOPE.md](MVP_SCOPE.md)를 REQUIREMENTS에서 공식화한 37개 MVP P0
시나리오를 외부 Operation에 연결합니다. `Target`은 최종 제품 계약이며 현재 코드의 구현
완료를 뜻하지 않습니다. 현재 구현 상태와 담당 이슈는
[SPEC_TRACEABILITY.md](SPEC_TRACEABILITY.md)의 같은 시나리오 ID를 기준으로 판정합니다.

표의 `CSRF`는 Session Cookie와 CSRF Header, `IK`는 필수 `Idempotency-Key`를 뜻합니다.
단일·목록·오류 Envelope와 인증·역할·소유권 오류는 공통 계약을 따릅니다. 일반 GET은 안전하게
재시도하고 상태 변경은 IK Replay가 명시된 Operation만 같은 Key로 재시도합니다.

| 시나리오 | Method·Path                                                               | 인증·리소스 권한        | Body·Header                        | 성공 Status·핵심 Response           | 대표 4xx                | 재시도·멱등                                 | 선행 → 후행       | Target 주석                       |
| -------- | ------------------------------------------------------------------------- | ----------------------- | ---------------------------------- | ----------------------------------- | ----------------------- | ------------------------------------------- | ----------------- | --------------------------------- |
| 1-2      | `POST /api/auth/login`, `GET /api/auth/session`                           | 비인증 → OWNER 본인     | 자격 증명·CSRF                     | 200 Session OWNER·Context           | 401, 403                | 로그인 자동 재시도 금지, Session GET 안전   | 가입 → 1-3        | 새로고침 복원                     |
| 1-3      | `POST /api/workplaces`, `PUT /api/workplaces/{id}/coordinates`            | OWNER·해당 사업장       | 기준정보·좌표·CSRF                 | 201 사업장, 204 위치 확정           | 400, 403, 409           | POST 자동 재시도 금지, 같은 좌표 PUT은 멱등 | 1-2 → 1-4         | 좌표 미확정이면 READY 차단        |
| 1-4      | `GET /api/wallet`, Client Routes                                          | OWNER 본인              | 없음                               | 200 가용·예치, 다섯 목적지          | 401, 403                | GET 안전                                    | 1-3 → 1-5         | 내비게이션은 Client 계약          |
| 1-5      | `POST /api/wallet/funding-orders`                                         | OWNER 본인 지갑         | 은행·계좌·금액·PIN·CSRF·IK         | 200 충전 결과                       | 400, 403, 409           | 같은 IK Replay                              | 1-4 → 1-6         | PIN 실패 사유 통합                |
| 1-6      | `GET /api/wallet/transactions`                                            | OWNER 본인 지갑         | Page·정렬 Query                    | 200 최신 거래 Page                  | 400, 401                | GET 안전                                    | 1-5 → 2-1         | 충전 즉시 재조회                  |
| 2-1      | `GET /api/workplaces/{id}/work-cases`                                     | OWNER·해당 사업장       | Page Query                         | 200 빈 `content` 허용               | 400, 403                | GET 안전                                    | 1-6 → 2-2         | 빈 목록은 오류 아님               |
| 2-2      | `POST /api/workplaces/{id}/work-cases`                                    | OWNER·해당 사업장       | 조건 Body·CSRF                     | 201 `{workCaseId,dailyWage}`        | 400, 403, 409           | 자동 재시도 금지                            | 2-1 → 2-3         | 입력 약정 일급을 그대로 저장      |
| 2-3      | `PATCH /api/work-cases/{id}`                                              | OWNER·DRAFT 소유자      | 전체 조건·CSRF                     | 204, GET에 수정 조건                | 400, 403, 409           | 자동 재시도 금지                            | 2-2 → 2-4         | 기존 초대 철회                    |
| 2-4      | 2-2 POST를 두 번 추가                                                     | OWNER·해당 사업장       | 각 조건 Body·CSRF                  | 서로 다른 201 두 건                 | 400, 409                | 각 요청 독립                                | 2-3 → 2-5         | 시간 중첩 정책은 검증 계약을 따름 |
| 2-5      | `POST /api/work-cases/{id}/invitations`                                   | OWNER·DRAFT 소유자      | `{healthCertificateRequired}`·CSRF | 200/201 Link·만료·요구 서류         | 403, 409                | 일반 발급으로 현재 Link 복구                | 2-4 → 3-1         | 보건증 요구 필드는 Target         |
| 3-1      | `/invitations/{token}` → `POST /api/auth/login`                           | 비인증 → WORKER         | Redirect·자격 증명·CSRF            | 로그인 뒤 원 경로 복귀              | 401, 403                | 로그인 자동 재시도 금지                     | 2-5 → 3-2         | Redirect 보존                     |
| 3-2      | `GET /api/invitations/{token}`                                            | WORKER·유효 Bearer 초대 | Token Path                         | 200 조건·보건증 요구·Badge          | 401, 403, 404, 409, 410 | GET 안전, 매번 재검증                       | 3-1 → 3-3         | 내부 Version·Key 미노출           |
| 3-3      | `POST /api/invitations/{token}/accept`                                    | WORKER·유효 초대        | 0byte·CSRF·IK                      | 200 Work·계약·문서·예치·정산 식별자 | 400, 403, 409, 410      | 같은 IK Replay, 재접속 복구 GET             | 3-2 → 3-4·3-6·4-2 | 수락 시 자금 재검증               |
| 3-4      | `GET /api/worker/work-cases`                                              | WORKER 본인             | Page Query                         | 200 확정 근무 Page                  | 401, 403                | GET 안전                                    | 3-3 → 5A-1        | 저장 상태 기준                    |
| 3-6      | `GET /api/documents`, `GET /api/documents/{id}/file`                      | 계약 OWNER·WORKER       | Page 또는 문서 ID                  | 200 SIGNED Metadata·PDF             | 403, 404, 500           | GET 안전                                    | 3-3 → 종료        | 비공개·Checksum 검증              |
| 4-2      | `GET /api/workplaces/{id}/work-cases`                                     | OWNER·해당 사업장       | Page Query                         | 200 계약 완료 상태                  | 403, 404                | GET 안전                                    | 3-3 → 4-3         | 저장 상태 기준                    |
| 4-3      | `GET /api/work-cases/{id}`                                                | OWNER·해당 Work         | 없음                               | 200 확정 조건, 수정·삭제 비가시     | 403, 404                | GET 안전                                    | 4-2 → 4-5         | 변경 API는 409                    |
| 4-5      | `GET /api/wallet`                                                         | OWNER 본인              | 없음                               | 200 가용 감소·예치 증가             | 401                     | GET 안전                                    | 3건 수락 → 4-6    | 총액 보존                         |
| 4-6      | `GET /api/workplaces/{id}/qr`                                             | OWNER·해당 사업장       | 없음                               | 200 고정 QR Token·Image 자료        | 403, 404, 500           | GET 안전                                    | 4-5 → 5A-1        | 인쇄·저장은 Client Target         |
| 5A-1     | `GET /api/worker/home`                                                    | WORKER A 본인           | 없음                               | 200 오늘 근무·출근 가능 시각        | 401, 404                | GET 안전                                    | 4-6 → 5A-2        | 실제 저장 근무                    |
| 5A-2     | `POST /api/attendance/scans`                                              | WORKER A·현장 Work      | QR·위치·CSRF·IK                    | 200 CHECK_IN·IN_PROGRESS            | 409, 422, 503           | 같은 IK Replay                              | 5A-1 → 5A-3·5A-5  | 후보·거리 재검증                  |
| 5A-3     | `GET /api/worker/home`                                                    | WORKER A 본인           | 없음                               | 200 약정 일급·현재 근무 상태        | 401, 409                | GET 안전                                    | 5A-2 → 5A-4       | 경과 금액 공식 없음              |
| 5A-4     | `POST /api/attendance/scans`                                              | WORKER A·IN_PROGRESS    | QR·위치·조기 확인·CSRF·IK          | 200 CHECK_OUT·COMPLETED·dueAt       | 409, 422, 503           | 같은 의도 같은 IK                           | 5A-3 → 5A-6       | 조기 확인은 새 IK                 |
| 5A-5     | `GET /api/workplaces/{id}/work-cases`                                     | OWNER·해당 사업장       | Page Query                         | 200 A `IN_PROGRESS`                 | 403, 404                | GET 안전                                    | 5A-2 → 5A-6       | 수동 재조회 즉시 반영             |
| 5A-6     | `POST /api/work-cases/{id}/settlement/approve`                            | OWNER·해당 Work         | 0byte·CSRF·IK                      | 200 전액 지급·완료 시각             | 403, 409                | 같은 IK Replay                              | 5A-4 → 5A-7·5A-8  | 정상 일당 전액                    |
| 5A-7     | `GET /api/wallet/transactions`                                            | OWNER 본인              | Page·검색 Query                    | 200 근무·수령인·금액 지급 원장      | 400, 401                | GET 안전                                    | 5A-6 → 종료       | 지급 직후 재조회                  |
| 5A-8     | `GET /api/wallet`, `GET .../transactions`, `POST .../withdrawal-requests` | WORKER A 본인           | 출금 계좌·금액·CSRF·IK             | 200 잔액·지급·출금 원장             | 403, 409                | 출금 같은 IK Replay                         | 5A-6 → 종료       | 지급 후 실제 지갑                 |
| 5B-1     | `GET /api/worker/home`                                                    | WORKER B 본인           | 없음                               | 200 B 오늘 근무                     | 401, 404                | GET 안전                                    | 4-6 → 5B-2        | 실제 저장 근무                    |
| 5B-2     | `POST /api/attendance/scans`                                              | WORKER B·현장 Work      | QR·위치·CSRF·IK                    | 200 CHECK_IN·`lateMinutes=30`       | 409, 422, 503           | 같은 IK Replay                              | 5B-1 → 5B-3       | 지각은 파생값                     |
| 5B-3     | `GET /api/worker/home`                                                    | WORKER B 본인           | 없음                               | 200 `lateMinutes=30`·약정 일급      | 401, 409                | GET 안전                                    | 5B-2 → 5B-4       | 자동 공제액 없음                 |
| 5B-4     | `GET /api/worker/home`                                                    | WORKER B 본인           | 없음                               | 200 약정 일급·현재 근무 상태        | 401, 409                | GET 안전                                    | 5B-3 → 5B-6       | 지각 금액 추정 금지              |
| 5B-5     | CHECK_OUT Scan 후 `GET /api/wallet`                                       | WORKER B 본인           | QR·위치·CSRF·IK                    | 200 약정 일급 전액 지급 반영        | 409, 422                | Scan Replay, GET 안전                       | 5B-6 → 종료       | 지급 승인 뒤 조회                |
| 5B-6     | `POST /api/work-cases/{id}/settlement/approve`                            | OWNER·해당 Work         | 0byte·CSRF·IK                      | 200 WORKER 전액 지급·OWNER 환불 0   | 403, 409                | 같은 IK Replay                              | 5B-4 → 5B-5·5B-7  | 지급액=원 예치액                 |
| 5B-7     | `GET /api/wallet`, `GET .../transactions`                                 | OWNER 본인              | Page Query                         | 200 전액 지급 원장, 예치 0          | 401, 409                | GET 안전                                    | 5B-6 → 종료       | 보존식 검증                       |
| 5C-1     | `GET /api/worker/home`                                                    | WORKER C 본인           | 없음                               | 200 경계 전 READY                   | 401, 404                | GET 안전                                    | 4-6 → 5C-2        | 조기 NO_SHOW 금지                 |
| 5C-2     | 시스템 Scheduler, 양측 GET                                                | 시스템·해당 당사자      | 제어 Clock/없음                    | 저장 `NO_SHOW`를 양측 200 조회      | 401, 403                | Scheduler 멱등                              | 5C-1 → 5C-3       | 시작+1시간 경계                   |
| 5C-3     | `POST /api/work-cases/{id}/settlement/no-show-refund/approve`             | OWNER·해당 NO_SHOW Work | 0byte·CSRF·IK                      | 200 OWNER 전액 환불·WORKER 0·예치 0 | 403, 409                | 같은 IK Replay                              | 5C-2 → 종료       | 자동 판정과 환불 승인 분리        |

## 공통 계약

### 성공 응답

단일 결과는 `data`로 감쌉니다.

```json
{
  "data": {
    "id": 1
  }
}
```

목록은 다음 Page Envelope를 사용합니다.

```json
{
  "data": {
    "content": [],
    "page": {
      "number": 0,
      "size": 20,
      "totalElements": 0,
      "totalPages": 0
    }
  }
}
```

`204 No Content`는 본문을 반환하지 않습니다.

### 오류 응답

```json
{
  "code": "ERROR_CODE",
  "message": "사용자가 이해할 수 있는 오류 설명",
  "traceId": "UUID",
  "fieldErrors": [
    {
      "field": "fieldName",
      "reason": "거부 사유"
    }
  ]
}
```

- `fieldErrors`는 필드 오류가 있을 때만 포함합니다.
- 인증 없음과 Session 만료는 401, 역할 또는 리소스 소유권 위반은 403입니다.
- 내부 SQL, Stack Trace, Token 원문과 타인의 리소스 존재 여부를 노출하지 않습니다.
- 승인된 공통·정산 오류 Code는 `VALIDATION_ERROR`, `AUTH_REQUIRED`, `FORBIDDEN`,
  `ROLE_MISMATCH`, `RESOURCE_NOT_FOUND`, `CONFLICT`, `IDEMPOTENCY_KEY_REUSED`,
  `WORK_CASE_LOCKED`, `CONTRACT_RETENTION_REQUIRED`, `SETTLEMENT_ON_HOLD`,
  `SETTLEMENT_NOT_READY`, `SETTLEMENT_ALREADY_PROCESSED`, `DISPUTE_ALREADY_OPEN`,
  `SETTLEMENT_TEMPORARILY_UNAVAILABLE`, `INTERNAL_ERROR`입니다.
- 아이디 없음·비밀번호 불일치·비활성 또는 잠금 계정은 이유를 구분하지 않고
  `401 AUTH_REQUIRED`로 응답합니다.
- CSRF 검증 실패는 `403 FORBIDDEN`, 중복 가입은 `409 CONFLICT`, 역할 불일치는
  `403 ROLE_MISMATCH`, 입력 검증 실패는 `400 VALIDATION_ERROR`입니다.
- 더 구체적인 승인 오류로 변환되지 않은 예상 밖의 서버 오류는
  `500 INTERNAL_ERROR`와 `서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.` 메시지로
  응답하며 `fieldErrors`를 포함하지 않습니다.
- `500 INTERNAL_ERROR` 응답의 `traceId`는 서버 오류 로그에도 같은 값으로 기록합니다. 내부
  예외 메시지, SQL, Stack Trace와 민감 정보는 서버 로그에서만 다루고 응답에는 노출하지
  않습니다. 단, PIN 원문이나 파생값은 오류·감사·SQL 바인딩을 포함한 어떤 로그에도 기록하지
  않습니다.
- Mock 계좌·지갑 금융 오류는 `DEC-BANK-ERROR-CATALOG`, 초대 오류는
  `DEC-INVITE-ERROR-CATALOG`, 고정 QR 조회·재발급 오류는 `DEC-QR-ERROR-CATALOG`를
  따릅니다. 근태 스캔 오류는 `DEC-ATTENDANCE-ERROR-CATALOG`, 정산·분쟁 오류는
  `DEC-SETTLEMENT-ERROR-CATALOG`, 문서 오류는 `DEC-DOCUMENT-ERROR-CATALOG`를 따릅니다.

### Session, CSRF와 로컬 CORS

- 인증은 `JSESSIONID` 기반 HttpSession입니다. 응답과 저장소에 JWT 또는 Access Token을
  만들지 않습니다.
- 로그인 성공 시 Session ID를 교체하고 로그아웃 시 Session을 무효화합니다.
- `JSESSIONID`는 `HttpOnly=true`, 로컬 환경에서 `Secure=false`, `SameSite=Lax`인 Host-only
  Cookie입니다.
- `GET /api/auth/csrf`는 `204 No Content`로 `XSRF-TOKEN` Cookie를 준비합니다.
- 앱 최초 실행, 로그인 성공 후, 로그아웃 성공 후 `GET /api/auth/csrf`를 다시 호출합니다.
- 로그인과 로그아웃 POST도 CSRF를 검증합니다.
- 상태 변경 요청은 Cookie 값을 `X-XSRF-TOKEN` Header로 보냅니다.
- CSRF 실패는 `403 FORBIDDEN`이며 실패한 상태 변경 요청을 자동 재실행하지 않습니다.
- 로컬 CORS 허용 Origin은 `http://localhost:5173` 하나이며 Credential을 허용합니다.
- 로컬 허용 요청 Header는 `Accept`, `Content-Type`, `X-XSRF-TOKEN`,
  `Idempotency-Key`입니다.
- 노출 응답 Header는 `Location`, `Idempotency-Replayed`입니다.

### 입력 정규화와 검증

입력은 정규화한 뒤 검증하며 가용성 조회와 실제 가입은 같은 규칙을 사용합니다.

- `loginId`: trim 후 소문자로 저장·비교하며 정규화한 값은 필수이고 최대 50자입니다.
- `email`: trim 후 소문자로 저장·비교하며 정규화한 값은 필수이고 최대 255자입니다.
- `loginId`와 `email`은 필수 여부와 최대 길이 외에 최소 길이, 허용 문자 조합 또는 추가
  이메일 형식 규칙을 강제하지 않습니다.
- `name`: trim만 적용하며 최대 100자입니다.
- `phone`: 공백과 하이픈을 제거한 뒤 `0`으로 시작하는 9~11자리 숫자인지 검증합니다.
- API는 정규화된 전화번호 숫자만 반환하고 화면 표시 형식은 클라이언트가 적용합니다.
- 비밀번호에는 trim이나 대소문자 변환을 적용하지 않습니다.
- 비밀번호는 8~64자이면서 UTF-8 기준 72byte 이하여야 합니다. 72byte를 넘는 입력은
  절단하지 않고 `400 VALIDATION_ERROR`로 거부하며 문자 종류 조합은 강제하지 않습니다.
- 전화번호 저장 대상은 서로 독립된 `users.phone`, `workplaces.phone`입니다.
  `employer_profiles.contact_phone`으로 옮기거나 이름을 변경하지 않습니다.
- 전화번호 값은 요청·응답·SQL 바인딩 로그에 남기지 않습니다.

### 시간과 날짜

- 생성, 완료, 만료, 기록, 확인 등 실제 발생 시점은 UTC ISO-8601 `Instant`의 `Z`
  문자열입니다.
- 날짜 자체가 의미인 값은 `YYYY-MM-DD` 형식의 `LocalDate`입니다.
- UI가 날짜와 시간을 분리해 보내는 근무 입력은 서버가 `Asia/Seoul` 기준으로 하나의
  시점으로 결합합니다.
- DB `DATETIME(6)`의 `Asia/Seoul` 값은 API 경계에서 UTC로 변환합니다.

```text
DB:  2026-07-31 18:00:00.000000  (Asia/Seoul)
API: 2026-07-31T09:00:00Z
```

### 페이지네이션

- `page`: 기본값 0
- `size`: 기본값 20, 허용 범위 1~100
- 날짜 범위 Query의 `from`, `to`는 `LocalDate`이며 `to` 날짜 전체를 포함합니다.

### 멱등 요청

다음 요청은 `Idempotency-Key` Header가 필수입니다.

- `POST /api/wallet/funding-orders`
- `POST /api/wallet/withdrawal-requests`
- `POST /api/invitations/{token}/accept`
- `POST /api/work-cases/{workCaseId}/settlement/approve`
- `POST /api/work-cases/{workCaseId}/settlement/no-show-refund/approve`
- `POST /api/attendance/scans`

Key는 공백 없는 출력 가능한 ASCII 1~100자입니다. 저장 범위는
`(인증 사용자, Operation, Idempotency-Key)` 조합이며 같은 사용자가 다른 Operation에서
같은 Key 문자열을 사용해도 충돌하지 않습니다. 같은 요청 여부는 검증을 통과한 정규화 값의
Fingerprint로 판정합니다.

- 충전 Fingerprint는 정규화한 `bankCode`, `accountNo`, `amount`만 포함하고 PIN과 PIN의
  Hash·HMAC 등 파생값은 포함하지 않습니다. 출금도 같은 세 필드를 사용합니다.
- 근태 스캔의 Operation은 `ATTENDANCE_SCAN`입니다. QR Token SHA-256 소문자 Hex, 후행 0을
  제거하고 `-0`을 `0`으로 만든 지수 없는 위도·경도·정확도, UTC `capturedAt`, 소문자
  `confirmEarlyCheckout`을 순서대로 LF 결합한 UTF-8 Bytes의 SHA-256을 Fingerprint로
  저장합니다. Token 원문과 WORKER 정밀 좌표는 Claim·일반 로그·오류에 저장하지 않습니다.
- OWNER 정상 지급 Operation은 `SETTLEMENT_APPROVE`, Fingerprint는
  `SHA-256(UTF-8("SETTLEMENT_APPROVE\n" + decimalWorkCaseId))`입니다. OWNER NO_SHOW 환불
  Operation은 `SETTLEMENT_NO_SHOW_REFUND_APPROVE`, Fingerprint는
  `SHA-256(UTF-8("SETTLEMENT_NO_SHOW_REFUND_APPROVE\n" + decimalWorkCaseId))`입니다.
  외부 Key 원문은 금융 원장 Key로 사용하지 않습니다.
- 형식 검증 실패와 계좌 인증 실패 등 자금 이동 전 실패는 저장·재생하지 않습니다. 실패
  과정에서 만든 `PROCESSING` Claim은 별도 짧은 트랜잭션에서 삭제하므로 같은 Key로 입력을
  고쳐 다시 시도할 수 있습니다.
- 저장한 성공 결과는 24시간 보존합니다. 완료 Replay는 새 자금 이동이 아니라 저장 결과
  조회이므로 현재 계좌 상태·잔액이나 PIN을 다시 확인하지 않고 같은 `data`를 `200`과
  `Idempotency-Replayed: true`로 반환합니다.
- 같은 Key를 다른 Fingerprint에 사용하면 `409 IDEMPOTENCY_KEY_REUSED`입니다.

#### Claim 상태 모델

멱등 처리는 요청 본체보다 먼저 Claim을 선점하는 `PROCESSING → COMPLETED` 모델을
사용합니다(`DEC-IDEMPOTENCY-CLAIM-LIFECYCLE`).

1. 구조 검증과 정규화 뒤 별도의 짧은 트랜잭션에서 `PROCESSING` Claim을 삽입하고
   Commit합니다.
2. Claim 선점 요청만 도메인 본 트랜잭션을 실행합니다. 충전의 계좌·PIN 인증 실패와 근태
   스캔의 의미상 거부를 포함해 본 처리가 실패하면 그 요청이 선점한 Claim을 삭제합니다.
3. 본 처리가 성공하면 HTTP 상태와 Body Snapshot, 완료·만료 시각을 기록하고
   `COMPLETED`로 전이합니다.

| 기존 Claim 상태 | Fingerprint | 응답                                                   |
| --------------- | ----------- | ------------------------------------------------------ |
| `COMPLETED`     | 같음        | 저장된 Body와 `200` + `Idempotency-Replayed: true`     |
| `COMPLETED`     | 다름        | `409 IDEMPOTENCY_KEY_REUSED`                           |
| `PROCESSING`    | 같음        | `409 CONFLICT` — 처리 중이며 잠시 후 같은 Key로 재시도 |
| `PROCESSING`    | 다름        | `409 IDEMPOTENCY_KEY_REUSED`                           |

중복 요청은 짧은 Claim 선점 트랜잭션이 끝난 뒤 상태를 판정하며 자금 본 처리의 잠금 해제를
기다리지 않습니다. 프로세스 중단으로 남은 `PROCESSING` Claim과 만료된 `COMPLETED` Claim은
`expires_at` 이후 정리합니다. Claim이 만료되어도 주문·요청과 양쪽 원장의 고유 제약으로 이미
완료된 자금 이동의 중복을 막습니다. Claim 선점·비교·실패 정리·만료 정리는 애플리케이션
책임이며 DB는 범위 유일성과 상태 필드 정합성을 강제합니다.

QR 재발급은 `Idempotency-Key`를 요구하지 않습니다. 응답을 확인하지 못한 클라이언트는
재발급 POST를 자동 반복하지 않고 현재 QR을 GET으로 복구합니다.

## 인증·회원

| Method | Path                                     | 인증         | 요청                            | 성공                                                          |
| ------ | ---------------------------------------- | ------------ | ------------------------------- | ------------------------------------------------------------- |
| GET    | `/api/auth/csrf`                         | 불필요       | 없음                            | `204` + `XSRF-TOKEN` Cookie 준비                              |
| GET    | `/api/auth/session`                      | Session 확인 | 없음                            | `200 {data:{authenticated,role?,name?,needsWorkplaceSetup?}}` |
| GET    | `/api/auth/login-id-availability`        | 불필요       | Query `loginId`                 | `200 {data:{available}}`                                      |
| GET    | `/api/auth/email-availability`           | 불필요       | Query `email`                   | `200 {data:{available}}`                                      |
| POST   | `/api/auth/signup`                       | 불필요       | 가입 Body                       | `201 {data:{userId}}`                                         |
| POST   | `/api/auth/login`                        | 불필요       | 로그인 Body                     | `200 {data:{role,name,needsWorkplaceSetup}}`                  |
| POST   | `/api/auth/logout`                       | 필요         | 없음                            | `204`                                                         |
| GET    | `/api/users/me`                          | 필요         | 없음                            | 내 프로필                                                     |
| PATCH  | `/api/users/me`                          | 필요         | `{phone}`                       | 변경된 내 프로필                                              |
| PATCH  | `/api/users/me/password`                 | 필요         | `{currentPassword,newPassword}` | `204`                                                         |
| POST   | `/api/users/me/withdrawal`               | 필요         | `{password}`                    | `204`                                                         |
| GET    | `/api/users/me/badge`                    | 필요         | 없음                            | 최신 뱃지                                                     |
| POST   | `/api/auth/password-reset/requests`      | 불필요       | `{email}`                       | `202 {data:{accepted:true}}`                                  |
| POST   | `/api/auth/password-reset/confirmations` | 불필요       | `{token,newPassword}`           | `204`                                                         |

### Session 조회

`GET /api/auth/session`은 공개 부트스트랩 API입니다. 비인증 상태는 다음 200 응답입니다.

```json
{
  "data": {
    "authenticated": false
  }
}
```

인증된 OWNER 응답에는 현재 DB를 기준으로 계산한 `needsWorkplaceSetup`을 포함합니다.

```json
{
  "data": {
    "authenticated": true,
    "role": "OWNER",
    "name": "김사장",
    "needsWorkplaceSetup": true
  }
}
```

`needsWorkplaceSetup`은 요청 시점에 `role == OWNER`이면서 `status=ACTIVE`인 소유 사업장이
0개일 때만 true입니다. 계산 결과를 Session에 저장하지 않고 로그인과 Session 조회마다
현재 DB 상태로 계산합니다. 다른 보호 API는 인증이 없거나 만료되면
`401 AUTH_REQUIRED`를 반환합니다.

### 가입

```json
{
  "loginId": "worker01",
  "password": "secret123",
  "passwordConfirm": "secret123",
  "name": "김근로",
  "email": "worker@example.com",
  "phone": "01012345678",
  "role": "WORKER"
}
```

- `role`은 `OWNER` 또는 `WORKER`입니다.
- `phone`만 선택 필드입니다.
- 가입은 `users`의 사용자와 KRW `wallets`만 함께 생성하며 Mock 은행계좌를 생성하거나
  사용자에게 귀속·연결하지 않습니다.
- OWNER 가입도 `employer_profiles`를 만들지 않습니다.
- OWNER의 사업장 입력을 가입 Body에 포함하지 않고 사업체·사업장 기준정보는 후속
  `workplaces` 등록에서 관리합니다.

### 로그인

```json
{
  "loginId": "owner01",
  "password": "secret123",
  "expectedRole": "OWNER"
}
```

서버의 실제 역할과 `expectedRole`이 다르면 `403 ROLE_MISMATCH`입니다. 성공 응답에 Token을
포함하지 않습니다. 아이디 없음, 비밀번호 불일치, 비활성 또는 잠금 계정은 모두
`401 AUTH_REQUIRED`로 응답하고 계정 존재 여부나 상태를 구분해 노출하지 않습니다.
OWNER의 `needsWorkplaceSetup`은 Session 조회와 같은 ACTIVE 사업장 실시간 기준으로 계산합니다.

### 내 프로필

`GET /api/users/me`와 `PATCH /api/users/me`의 `data`는 다음 필드를 반환합니다.

```json
{
  "data": {
    "loginId": "owner01",
    "email": "owner@example.com",
    "name": "김사장",
    "phone": "01012345678",
    "role": "OWNER",
    "status": "ACTIVE"
  }
}
```

PATCH Body는 `phone`만 허용합니다. `loginId`, `email`, `name`, `role`, `status`를 보내면
무시하지 않고 `400 VALIDATION_ERROR`로 거부합니다. 전화번호는 공통 정규화 규칙을 적용한
숫자 문자열로 저장·반환합니다.

### 비밀번호 입력

가입, 로그인, 비밀번호 변경과 비밀번호 재설정의 비밀번호 입력은 공통 8~64자 및 UTF-8
72byte 이하 경계를 사용합니다. 비밀번호와 확인값은 변환하지 않은 원문이 같아야 하며,
새 비밀번호를 72byte에서 절단하거나 문자 종류 조합을 추가로 강제하지 않습니다.

### 최신 뱃지

`GET /api/users/me/badge`는 `badgeType`, `level`, `recentCount`,
`remainingToNextLevel`, `criterionLabel`, `criterionDesc`를 `data`에 반환합니다.

- 역할에 따라 `badgeType`은 `TRUST_OWNER` 또는 `TRUST_WORKER`이고, 이력이 없어도
  `level=0`, `recentCount=0`인 객체를 반환합니다.
- `recentCount`는 호환 필드명이며 최근 구간이 아니라 누적 건수입니다. 1·2·3단계는 각각
  누적 10·20·30건과 정상 비율 80·90·100%를 모두 만족해야 하며 높은 단계부터 판정합니다.
  비율은 반올림하지 않고 `normalCount * 100 >= totalCount * thresholdPercent`로 비교합니다.
- OWNER 누적 건수는 지급자인 `COMPLETED` Settlement 수이고, 정상 건수는 그중 같은
  Work Case에 `CANCELED`·`REJECTED`가 아닌 분쟁이 없는 수입니다.
- 분쟁 기능이 아직 구현되지 않은 동안에는 완료 Settlement를 정상으로 세며, 기능 도입 뒤
  분모·분자 정의를 바꾸지 않고 실제 분쟁 행만 반영합니다.
- WORKER 누적 건수는 본인의 `COMPLETED`·`NO_SHOW`·`CHECK_OUT_MISSING` Work Case 수이고,
  정상 건수는 `COMPLETED`이면서 성공 CHECK_IN `attemptedAt <= startsAt`인 수입니다.
- `remainingToNextLevel`은 다음 단계 건수 문턱까지 남은 수입니다. 건수는 충족했지만 정상
  비율이 부족하면 0이고 `criterionDesc`가 부족한 비율 조건을 안내하며 3단계도 0입니다.
- `criterionLabel`은 OWNER `안심거래`, WORKER `성실근로`입니다. 조회는 사용자 행을 잠근 뒤
  현재 Commit된 원천 이력을 다시 계산하고 같은 트랜잭션의 Member/Auth 경계에서
  `user_badges`를 Upsert한 뒤 Commit하고 응답합니다. 뱃지 행이 없어도 사용자 행이 잠금
  기준입니다. 별도 Backfill Batch는 없으며 첫 조회가 배포 전 이력까지 계산합니다.
- `criterionDesc`는 누적 건수·정상 건수와 다음 단계의 건수·정상 비율 조건을 함께
  설명합니다. Work·Attendance·Settlement는 `user_badges`를 직접 쓰지 않고, 초대 조회도
  Member/Auth가 공개한 Badge Application 경계를 사용하며 이 호출 방향을 Module Boundary
  Manifest에 등록합니다.
- evidence는 `ruleVersion=trust-badge-cumulative-10-20-30-v1`, badgeType, level,
  totalCount, normalCount, 적용 thresholdCount·thresholdPercent, calculatedAt만 저장합니다.
  새 Column은 추가하지 않습니다. 0단계 문턱은 둘 다 0이며 원천 행 ID와 개인정보는
  저장하지 않습니다.

### 비밀번호 재설정

- 요청은 이메일 존재 여부와 무관하게 같은 202 응답을 반환합니다.
- Token 원문 전달 채널은 `DEC-OPEN-PASSWORD-RESET-DELIVERY`를 따릅니다.
- 확인은 유효하고 사용되지 않은 Token만 한 번 허용하며 성공 시 기존 Session 정책에 따라
  인증 상태를 갱신합니다.
- `newPassword`는 공통 비밀번호 경계를 적용합니다.

## 사업장

| Method | Path                                        | 권한       | 요청                | 성공                       |
| ------ | ------------------------------------------- | ---------- | ------------------- | -------------------------- |
| POST   | `/api/workplaces`                           | OWNER      | 사업장 등록 Body    | `201 {data:{workplaceId}}` |
| GET    | `/api/workplaces`                           | OWNER      | 공통 Page Query     | 사업장 목록                |
| PATCH  | `/api/workplaces/{workplaceId}`             | 해당 OWNER | 허용 필드           | 변경된 사업장              |
| PUT    | `/api/workplaces/{workplaceId}/coordinates` | 해당 OWNER | 현장 위치 확정 Body | `204`                      |
| DELETE | `/api/workplaces/{workplaceId}`             | 해당 OWNER | 없음                | `204`                      |

### 사업장 등록

```json
{
  "businessRegistrationNumber": "1234567890",
  "name": "강남점",
  "representativeName": "김사장",
  "roadAddress": "서울 강남구 테헤란로 1",
  "detailAddress": "2층",
  "phone": "0212345678",
  "latitude": 37.123,
  "longitude": 127.123
}
```

- `detailAddress`, `latitude`, `longitude`는 선택값입니다.
- 위도와 경도는 함께 보내거나 모두 생략합니다.
- 현장 브라우저가 좌표를 공급하고 OWNER가 등록 동작으로 명시적으로 확인합니다. 서버는 주소를
  지오코딩하거나 좌표를 추정하지 않습니다.
- `radiusMeters`, `radiusM`은 받지 않으며 서버가 100m를 적용합니다.
- `phone`은 공통 전화번호 정규화 후 숫자 문자열로 저장·반환합니다.
- 등록 성공 시 같은 트랜잭션에서 그 사업장의 활성 고정 QR 한 건을 발급합니다. 발급자는
  사업장 OWNER이며, 사업장 또는 QR 저장 중 하나라도 실패하면 둘 다 Rollback합니다.

### OWNER 사업장 목록

`GET /api/workplaces`는 공통 Page Envelope를 사용하며, 각 `content` Item은 다음 필드만
반환합니다.

```json
{
  "workplaceId": 1,
  "businessRegistrationNumber": "1234567890",
  "name": "강남점",
  "representativeName": "김사장",
  "roadAddress": "서울 강남구 테헤란로 1",
  "detailAddress": "2층",
  "phone": "0212345678",
  "radiusMeters": 100,
  "attendanceLocationConfirmed": false,
  "status": "INACTIVE"
}
```

- OWNER가 소유한 `ACTIVE`, `INACTIVE` 사업장을 반환합니다.
- `DELETED` 사업장은 반환하지 않습니다.
- 목록 Item에 `latitude`, `longitude`를 포함하지 않습니다.
- 두 좌표가 모두 존재하면 `attendanceLocationConfirmed=true`, 모두 없으면 `false`입니다.
- 전역 작업 Context로 선택할 수 있는 사업장은 기존 계약대로 `ACTIVE`만 허용합니다.

### 사업장 수정

허용 필드는 `name`, `roadAddress`, `detailAddress`, `phone`입니다.
`businessRegistrationNumber`, `representativeName`, `latitude`, `longitude`,
`radiusMeters`를 보내면 `400 VALIDATION_ERROR`입니다. 좌표가 확정된 사업장의 도로명·상세
주소 변경은 `409 WORKPLACE_LOCATION_LOCKED`이고 상호·전화번호 변경은 유지합니다.

### 사업장 출퇴근 위치 확정

좌표가 없는 `ACTIVE` 소유 사업장은
`PUT /api/workplaces/{workplaceId}/coordinates`로 현장 위치를 한 번 확정합니다.

```json
{
  "latitude": 37.1234567,
  "longitude": 127.1234567,
  "accuracyMeters": 18.25,
  "capturedAt": "2026-08-07T01:00:00Z"
}
```

- 위도·경도는 각각 소수점 7자리 이하와 지리 범위, 정확도는 0 이상 100 이하의 소수점
  2자리 이하입니다.
- `capturedAt`은 서버 수신 시각보다 정확히 5분 전부터 1분 후까지 포함합니다.
- 서버는 소유권·ACTIVE 상태와 좌표 미확정을 검증한 뒤 두 좌표만 사업장 기준정보로
  저장합니다. 정확도와 측정 시각은 저장하지 않습니다.
- 최초 성공과 같은 정규화 좌표 재전송은 204, 다른 값은
  `409 WORKPLACE_COORDINATES_ALREADY_SET`입니다.
- 형식·범위·신선도·정확도 오류는 `400 VALIDATION_ERROR`, 없는 사업장 또는 다른 OWNER는
  `404 RESOURCE_NOT_FOUND`입니다.

## WORKER 홈과 근무 이력

| Method | Path                     | 권한   | 성공                         |
| ------ | ------------------------ | ------ | ---------------------------- |
| GET    | `/api/worker/home`       | WORKER | 오늘 근무와 계산 기준        |
| GET    | `/api/worker/work-cases` | WORKER | 본인 근무 목록               |
| GET    | `/api/worker/workplaces` | WORKER | 보건증 공유 가능 사업장 목록 |

`GET /api/worker/home`의 `todayWorkCase`는 없으면 `null`이고, 있으면 다음 필드를
반환합니다.

- `workCaseId`, `title`, `workplaceName`, `startsAt`, `endsAt`
- `breakMinutes`, `breakPaid`, `dailyWage`, `expectedNetAmount`, `status`
- `attendance:{checkedInAt,checkedOutAt,isLate,lateMinutes}`
- `escrowStatus`, `settlementStatus`, `settlementDueAt`

`GET /api/worker/home`과 `GET /api/worker/work-cases`의 금액 조건은 약정 일급
`dailyWage`입니다. `hourlyWage`, `expectedDeductionAmount`, `expectedPaymentAmount`를 반환하지
않고 `expectedNetAmount`는 저장 `dailyWage`만으로 계산합니다. 클라이언트는 일급에서 시급이나
지각 공제액을 역산하거나 임의 필드로 대체하지 않습니다.

출퇴근 시점은 nullable이고 지각 여부와 분수는 성공 CHECK_IN에서 파생합니다. 오늘 후보는
`Asia/Seoul` 시작일이 오늘인 배정 근무와 전날부터 남은 `IN_PROGRESS`,
`CHECK_OUT_MISSING`입니다. 복수이면 `IN_PROGRESS`, `CHECK_OUT_MISSING`, `READY`, `ACCEPTED`,
`COMPLETED`, `NO_SHOW` 순서, 같은 상태에서는 `startsAt ASC, workCaseId ASC`로 한 건을 고릅니다.

`expectedNetAmount`는 다음 계약으로 계산합니다.

```text
taxableBase = max(dailyWage - 150000, 0)
incomeTax = taxableBase × 0.027, 10원 미만 절사
localIncomeTax = taxableBase × 0.0027, 10원 미만 절사
if incomeTax < 1000:
  incomeTax = 0
  localIncomeTax = 0
expectedNetAmount = dailyWage - incomeTax - localIncomeTax
```

시간 경과 확보 안심금액과 지각 공제·상한은 현재 MVP 계약에 없습니다. 클라이언트는 현재
응답의 일급 기반 `expectedNetAmount`를 참고값으로 사용할 수 있지만 독자적인 시급·경과·
공제식을 만들거나 API를 매분 재호출하지 않습니다. 해당 값을 도입하려면 별도 제품 결정과 새
명세 Patch가 필요합니다.

`GET /api/worker/work-cases`의 각 Page Item은 같은 기본 근무 필드와 근태·Escrow·Settlement
상태를 반환합니다. 저장 상태를 `BEFORE_WORK`, `LATE`, `SETTLED` 같은 화면 별칭으로 바꾸지
않고 `CHECK_OUT_MISSING`을 `NO_SHOW`와 구분합니다. 두 API는 정밀 좌표, QR Token, OWNER
잔액과 계약 Storage 정보를 반환하지 않습니다.

근무 이력은 배정 확정 이후 상태만 반환하며 `DRAFT`·`CANCELED`는 content와
`totalElements`에서 함께 제외합니다. 저장된 Work Case 상태와 성공 근태 기록이 모순이면
저장값을 반환하되 내부 무결성 신호를 남기며, 한 손상 행 때문에 본인 조회 전체를 실패시키지
않습니다.

`GET /api/worker/workplaces`는 보건증 신규 공유 후보가 될 수 있는 관계를
`startsAt ASC, workplaceId ASC`로 반환합니다. Item은 `workplaceId`, `workplaceName`,
`ownerName`, `startsAt`, `endsAt`만 포함합니다. ACTIVE 사업장과 인증 WORKER의
`ACCEPTED`·`READY` Work Case만 후보이며 이 Endpoint는 `documentId`를 받지 않으므로 특정
문서의 만료나 중복 공유는 판정하지 않습니다. 실제 공유 POST에서 이를 다시 검증합니다.
응답은 공통 `{data:{content,page}}` 목록 Envelope를 사용합니다.

## 지갑과 거래

| Method | Path                              | 권한        | 요청                                 | 성공           |
| ------ | --------------------------------- | ----------- | ------------------------------------ | -------------- |
| GET    | `/api/wallet`                     | 인증 사용자 | 없음                                 | 잔액           |
| GET    | `/api/wallet/transactions`        | 인증 사용자 | 거래 Query                           | 거래 Page      |
| POST   | `/api/wallet/funding-orders`      | OWNER       | `{bankCode, accountNo, pin, amount}` | 충전 처리 결과 |
| POST   | `/api/wallet/withdrawal-requests` | 인증 사용자 | `{bankCode, accountNo, amount}`      | 출금 처리 결과 |

### Mock 은행계좌 경계

Mock 은행계좌는 서비스 회원보다 먼저 Demo/Disposable Seed로 생성한 합성 Fixture입니다
(`DEC-MOCK-ACCOUNT-FIXTURE`). Gig Hub 사용자에게 소유·귀속·연결하지 않으며
`mock_bank_accounts.user_id`를 사용하지 않습니다.

- 가입, 프로필 또는 별도 제품 API에서 계좌를 생성·등록·연결하지 않습니다.
- 사용자용 `GET /api/mock-bank-accounts`와 계좌 CRUD·연결 API를 제공하지 않습니다.
- 서버는 정규화한 `bankCode + accountNo`로 계좌를 식별하고 기존 내부 ID를
  `funding_orders.linked_account_id`, `withdrawal_requests.linked_account_id`와
  `mock_bank_transactions.account_id`에 기록합니다.
- 내부 `bankAccountId`, `mockFintechUseNum`, Mock 계좌 잔액과 PIN은 외부 요청·응답으로
  노출하지 않습니다.
- 이체 확인 화면은 클라이언트가 입력값을 마스킹해 확인하고 PIN을 받는 단계입니다. 별도
  REST Operation이나 계좌 등록·연결·조회 절차가 아닙니다.

지원하는 canonical 은행 코드는 다음 20개입니다. 별칭(`KB`, `SHINHAN` 등)은 API 값으로
허용하지 않습니다.

| `bankCode` | 은행명      |
| ---------- | ----------- |
| `004`      | KB국민은행  |
| `088`      | 신한은행    |
| `020`      | 우리은행    |
| `081`      | 하나은행    |
| `011`      | NH농협은행  |
| `003`      | 기업은행    |
| `090`      | 카카오뱅크  |
| `092`      | 토스뱅크    |
| `089`      | 케이뱅크    |
| `032`      | 부산은행    |
| `031`      | DGB대구은행 |
| `131`      | iM뱅크      |
| `034`      | 광주은행    |
| `023`      | SC제일은행  |
| `027`      | 씨티은행    |
| `002`      | KDB산업은행 |
| `007`      | 수협은행    |
| `045`      | 새마을금고  |
| `048`      | 신협        |
| `071`      | 우체국      |

`131`은 화면에서 DGB대구은행과 별도 선택지인 iM뱅크를 구분하기 위한 프로젝트 전용
canonical 코드입니다. 이 코드표는 합성 Mock 계좌 시연 계약이며 실제 금융기관 연동이나
외부 기관 코드의 정확성을 보장하지 않습니다.

계좌·금액 입력 검증은 다음과 같습니다(`DEC-BANK-INPUT-VALIDATION`).

- `bankCode`: 위 코드표의 숫자 3자리 문자열
- `accountNo`: 공백과 하이픈을 제거한 뒤 10~14자리 숫자
- `amount`: 0보다 크고 100,000,000 이하인 KRW 원 단위 정수
- `pin`: 충전에만 필요하며 정규화하거나 trim하지 않은 정확히 4자리 ASCII 숫자
- 위 조건을 벗어난 입력은 `400 VALIDATION_ERROR`

Demo 계좌 PIN은 모두 `0000`입니다. 실행 시 인증 기준 값은
`mock_bank_accounts.pin`에만 두며 요청 PIN은 계좌 인증 시점에만 비교합니다. 주문·원장·
멱등 Fingerprint와 응답 Snapshot·응답·예외 메시지·오류·감사·SQL 바인딩을 포함한 모든
로그에는 PIN 원문이나 파생값을 저장하지 않습니다.

### 잔액

```json
{
  "data": {
    "currency": "KRW",
    "availableBalance": 100000,
    "lockedBalance": 50000
  }
}
```

### 거래 목록

Query:

- `workplaceId?`: 지정하면 해당 사업장 거래만 반환하며 `FUNDING`, `WITHDRAWAL`처럼
  사업장과 무관한 거래는 제외합니다.
- `from?`, `to?`
- `type?`: `FUNDING`, `ESCROW_HOLD`, `ESCROW_RELEASE`, `ESCROW_REFUND`,
  `WITHDRAWAL`, `WITHDRAWAL_REFUND`, `ADJUSTMENT`
- `minAmount?`, `maxAmount?`: `amount` 절대값 기준
- `keyword?`: `workTitle`, `workplaceName` 부분 일치
- `sort?`: 기본 `LATEST`, 또는 `OLDEST`, `AMOUNT_ASC`, `AMOUNT_DESC`이며 금액 정렬은
  절대값 기준
- `page?`, `size?`

Item은 `transactionId`, `type`, `amount`, `direction`, `availableAfter`, `lockedAfter`,
`workCaseId`, `workTitle`, `workplaceName`, `displayStatus`, `createdAt`을 반환합니다
(`DEC-TRANSACTION-DISPLAY`).

- `amount`는 항상 0 이상의 절대값이며 부호를 포함하지 않습니다.
- `direction`은 해당 거래 Row가 속한 사용자 지갑 기준 `CREDIT`(증가) 또는
  `DEBIT`(감소)입니다. 같은 `ESCROW_RELEASE` 사건도 OWNER Row는 `DEBIT`, WORKER Row는
  `CREDIT`입니다. `ADJUSTMENT`도 그 Row의 실제 지갑 증감으로 결정합니다.
- 사업장·근무와 무관한 거래의 `workCaseId`, `workTitle`, `workplaceName`은 `null`입니다.
- `displayStatus`는 `PENDING`, `COMPLETED`, `FAILED`, `REFUNDED` 중 하나입니다.
  `FUNDING`, `WITHDRAWAL`, `ESCROW_HOLD`, `ESCROW_RELEASE`, `ADJUSTMENT`는 원장 반영
  시점에만 Row를 생성하므로 `COMPLETED`, `ESCROW_REFUND`는 `REFUNDED`입니다.
  `PENDING`과 `FAILED`는 이번 릴리스에서 생성하지 않는 예약값입니다.
- `createdAt`은 UTC `Instant`입니다.

### 충전과 출금

충전 요청:

```json
{
  "bankCode": "004",
  "accountNo": "170000000001",
  "pin": "0000",
  "amount": 100000
}
```

출금 요청:

```json
{
  "bankCode": "004",
  "accountNo": "170000000001",
  "amount": 100000
}
```

충전은 OWNER가 ACTIVE Mock 계좌의 PIN을 인증해 그 계좌에서 지갑으로 자금을 옮기는
Operation입니다. 신규 실행에서 계좌 Row를 잠근 뒤 상태·PIN·잔액을 확인하고 계좌 차감,
은행 원장, 충전 주문 완료, 지갑 증가와 지갑 원장을 하나의 자금 트랜잭션으로 처리합니다.

출금은 인증 사용자의 지갑에서 ACTIVE Mock 계좌로 자금을 입금하는 Operation입니다. 입금
대상의 존재와 ACTIVE 상태만 확인하며 계좌 소유권이나 PIN을 검사하지 않습니다. 출금 요청
완료, 지갑 감소, 계좌 입금과 양쪽 원장을 하나의 자금 트랜잭션으로 처리합니다.

두 Operation 모두 서버가 `bankCode + accountNo`로 찾은 내부 ID를 기존
`linked_account_id`에 기록합니다. 클라이언트는 `bankAccountId`를 보내지 않고 성공 응답도
이를 반환하지 않습니다.

계좌·금액 오류는 다음 경계를 사용합니다(`DEC-BANK-ERROR-CATALOG`).

| 상황                                                   | Code               | HTTP  | `message`                                          |
| ------------------------------------------------------ | ------------------ | ----- | -------------------------------------------------- |
| 은행 코드·계좌번호·PIN·금액 형식 오류                  | `VALIDATION_ERROR` | `400` | `입력값을 확인해 주세요.`                          |
| 충전 계좌 미존재·비활성·PIN 불일치                     | `FORBIDDEN`        | `403` | `계좌를 사용할 수 없습니다.`                       |
| 출금 입금계좌 미존재·비활성                            | `FORBIDDEN`        | `403` | `계좌를 사용할 수 없습니다.`                       |
| 충전할 Mock 계좌 잔액 부족                             | `CONFLICT`         | `409` | `계좌 잔액이 부족합니다.`                          |
| 출금할 지갑 가용 잔액 부족                             | `CONFLICT`         | `409` | `출금 가능한 잔액이 부족합니다.`                   |
| 계좌·지갑 동시 갱신 충돌 또는 처리 중인 같은 멱등 요청 | `CONFLICT`         | `409` | `요청이 충돌했습니다. 잠시 후 다시 시도해 주세요.` |

미존재·비활성·PIN 불일치는 동일한 Code·HTTP 상태·메시지이며 `fieldErrors`를 포함하지
않습니다. 응답만으로 계좌 존재, 상태 또는 PIN 일치 여부를 구분할 수 없습니다.

최초 충전 성공:

```json
{
  "data": {
    "fundingOrderId": 10,
    "status": "COMPLETED",
    "bankTransactionId": 20
  }
}
```

최초 출금 성공:

```json
{
  "data": {
    "withdrawalRequestId": 11,
    "status": "COMPLETED",
    "bankTransactionId": 21
  }
}
```

최초 성공은 201, 멱등 재전송은 같은 `data`와 200을 반환합니다. 성공 응답에는 최신 잔액을
넣지 않으며 클라이언트가 잔액 API를 다시 조회합니다.

## OWNER 근무 관리

| Method | Path                                               | 권한        | 계약                               |
| ------ | -------------------------------------------------- | ----------- | ---------------------------------- |
| GET    | `/api/workplaces/{workplaceId}/work-cases/summary` | 해당 OWNER  | 8개 상태별 건수                    |
| GET    | `/api/workplaces/{workplaceId}/work-cases`         | 해당 OWNER  | 검색·상태·날짜 필터 Work Case Page |
| POST   | `/api/workplaces/{workplaceId}/work-cases`         | 해당 OWNER  | `DRAFT` 생성                       |
| GET    | `/api/work-cases/{workCaseId}`                     | 당사자      | 조건과 Aggregate 상세              |
| PATCH  | `/api/work-cases/{workCaseId}`                     | 해당 OWNER  | `DRAFT` 조건 전체 교체             |
| DELETE | `/api/work-cases/{workCaseId}`                     | 해당 OWNER  | Hard Delete 또는 `CANCELED`        |
| POST   | `/api/work-cases/{workCaseId}/invitations`         | 해당 OWNER  | 활성 초대 발급·조회                |
| POST   | `/api/work-cases/{workCaseId}/invitations/reissue` | 해당 OWNER  | 활성 초대 원자적 교체              |
| GET    | `/api/work-cases/{workCaseId}/workplace-contact`   | 해당 WORKER | `{ownerName,phone}`                |
| GET    | `/api/work-cases/{workCaseId}/disputes`            | 당사자      | 신고 Page                          |
| POST   | `/api/work-cases/{workCaseId}/disputes`            | 당사자      | `{title,content}` → `{reportId}`   |

### `GET /api/workplaces/{workplaceId}/work-cases/summary`

- 해당 사업장의 OWNER만 호출하고 Query는 받지 않습니다.
- 취소를 포함한 전체 Work Case를 `DRAFT`, `ACCEPTED`, `READY`, `IN_PROGRESS`,
  `CHECK_OUT_MISSING`, `COMPLETED`, `NO_SHOW`, `CANCELED`로 한 번씩만 집계합니다.
- 데이터가 없는 상태도 Key를 생략하지 않고 0을 반환합니다.

```json
{
  "data": {
    "draft": 2,
    "accepted": 1,
    "ready": 3,
    "inProgress": 1,
    "checkOutMissing": 0,
    "completed": 8,
    "noShow": 1,
    "canceled": 2
  }
}
```

### `GET /api/workplaces/{workplaceId}/work-cases`

Query 계약은 다음과 같습니다.

- `keyword?`: trim한 제목 또는 매칭 WORKER 이름의 대소문자 구분 없는 부분 일치입니다.
  trim 결과가 비면 미지정과 같습니다.
- `status?`: 8개 Work Case 상태 중 하나이며 미지정이면 전체 상태입니다.
- `from?`, `to?`: `Asia/Seoul` 기준 `workDate`의 `LocalDate`이고 양끝을 포함합니다. 둘 다
  있으면 `from <= to`여야 합니다.
- `page?`, `size?`: `DEC-PAGE`의 `page=0`, `size=20`, 최대 100을 따릅니다.
- 별도 정렬 Query는 받지 않고 `starts_at DESC, id DESC`로 고정합니다.

목록 Item은 아래 닫힌 필드 집합을 사용합니다. 매칭이 없으면 객체 내부 필드를 nullable로
만들지 않고 `worker` 전체를 `null`로 반환합니다.

```json
{
  "data": {
    "content": [
      {
        "workCaseId": 101,
        "title": "주말 홀 서빙",
        "workDate": "2026-08-20",
        "startsAt": "2026-08-20T01:00:00Z",
        "endsAt": "2026-08-20T09:00:00Z",
        "dailyWage": 120000,
        "status": "READY",
        "worker": {
          "workerId": 42,
          "name": "이알바"
        }
      },
      {
        "workCaseId": 100,
        "title": "평일 주방 보조",
        "workDate": "2026-08-19",
        "startsAt": "2026-08-19T00:00:00Z",
        "endsAt": "2026-08-19T06:00:00Z",
        "dailyWage": 90000,
        "status": "DRAFT",
        "worker": null
      }
    ],
    "page": {
      "number": 0,
      "size": 20,
      "totalElements": 2,
      "totalPages": 1
    }
  }
}
```

### `POST /api/workplaces/{workplaceId}/work-cases`

Target 요청은 `title`, `description`, `workDate`, `startTime`, `endTime`, `breakMinutes`,
`breakPaid`, `dailyWage`를 사용하고 성공은 `201 {data:{workCaseId,dailyWage}}`입니다.
`dailyWage`는 양의 KRW 원 단위 정수이며 서버는 입력값을 `work_cases.agreed_wage`에 그대로
저장합니다. 시급을 저장·역산하거나 근무시간과 휴게조건으로 일급을 다시 계산하지 않습니다.

1. 날짜와 시간을 `Asia/Seoul` 지역 시각으로 결합하고 `endsAt > startsAt`을 검증합니다.
2. `workplaces.road_address`를 trim하고, trim한 `detail_address`가 비어 있지 않을 때만 한 칸을
   사이에 두어 결합합니다.
3. 사업장 이름, 결합 주소, 좌표와 100m 반경을 Work Case Snapshot으로 복사합니다.
4. 조회의 `workDate`는 저장한 `startsAt`에서 파생하고 요청용 `startTime`, `endTime`은 응답하지
   않습니다.

### `GET /api/work-cases/{workCaseId}`

- 해당 Work Case의 OWNER 또는 매칭 WORKER만 호출합니다. 미매칭 `DRAFT`에는 OWNER만
  당사자입니다.
- 아래 JSON의 Key가 전체 상세 필드 집합입니다. 문서 본문, 서명 증거, 좌표, 인증 반경,
  전화번호와 Capability를 포함하지 않습니다.
- `latestInvitation`은 조건 Version과 관계없이 생성 시각과 ID 내림차순의 최신 초대 한
  건입니다.
- 근태 시점은 각 유형의 `SUCCESS` 기록 `captured_at`이며 거절 시도는 포함하지 않습니다.

```json
{
  "data": {
    "workCaseId": 101,
    "title": "주말 홀 서빙",
    "workDate": "2026-08-20",
    "startsAt": "2026-08-20T01:00:00Z",
    "endsAt": "2026-08-20T09:00:00Z",
    "breakMinutes": 60,
    "breakPaid": false,
    "dailyWage": 120000,
    "status": "COMPLETED",
    "termsVersion": 3,
    "workplaceName": "강남점",
    "workplaceAddress": "서울특별시 강남구 테헤란로 1 2층",
    "worker": {
      "workerId": 42,
      "name": "이알바"
    },
    "latestInvitation": {
      "status": "ACCEPTED",
      "termsVersion": 3,
      "expiresAt": "2026-08-20T01:00:00Z"
    },
    "contract": {
      "contractId": 31,
      "documentId": 51,
      "sourceTermsVersion": 3,
      "acceptedAt": "2026-08-10T04:00:00Z"
    },
    "attendance": {
      "checkedInAt": "2026-08-20T01:00:00Z",
      "checkedOutAt": "2026-08-20T09:00:00Z"
    },
    "escrow": {
      "status": "RELEASED",
      "amount": 120000
    },
    "settlement": {
      "status": "COMPLETED",
      "amount": 120000,
      "dueAt": "2026-08-21T00:00:00Z",
      "completedAt": "2026-08-21T00:05:00Z"
    }
  }
}
```

중첩 객체의 `null` 규칙은 다음과 같습니다.

- `worker`: `work_cases.worker_id`가 없으면 `null`입니다.
- `latestInvitation`: 초대 이력이 없으면 `null`이며, 있으면 `status`, 외부
  `termsVersion`, `expiresAt`을 반환합니다.
- `contract`: `work_contracts`가 없으면 `null`입니다. 있으면 같은 Work Case의
  `EMPLOYMENT_CONTRACT`를 연결해 `contractId`, `documentId`, `sourceTermsVersion`,
  `acceptedAt`을 반환합니다. 클라이언트는 수락 응답의 `workCaseId`로 이 상세를 조회하고
  `contract.documentId`로 계약 파일 API를 호출합니다. 계약은 있지만 연결 문서가 없으면
  부분 객체나 `null` 대신 `500 INTERNAL_ERROR`와 `traceId` 무결성 로그를 남깁니다.
- `attendance`: 항상 객체이고 성공 출근·퇴근이 없으면 각 시점이 `null`입니다.
- `escrow`: 행이 없으면 `null`, 있으면 `status`, `amount`를 반환합니다.
- `settlement`: 행이 없으면 `null`, 있으면 `status`, `amount`, nullable `dueAt`, nullable
  `completedAt`을 반환합니다.

### `PATCH /api/work-cases/{workCaseId}`

- 해당 OWNER만 호출하며 `title`, `description`, `workDate`, `startTime`, `endTime`,
  `breakMinutes`, `breakPaid`, `dailyWage` 여덟 필드를 모두 요구합니다. 생략과 명시적 `null`은
  `400 VALIDATION_ERROR`입니다.
- `dailyWage`는 양의 KRW 원 단위 정수이며 입력값을 약정 일급으로 그대로 저장합니다. 시급이나
  근무시간에서 다시 계산하지 않습니다.
- `DRAFT`가 아니면 `409 WORK_CASE_LOCKED`입니다.
- 값이 같더라도 성공 요청마다 `terms_version`을 정확히 1 증가시킵니다.
- 현재 조건 Version의 `PENDING` 초대를 같은 트랜잭션에서 `REVOKED`로 전이합니다.
- 주소, 좌표, 반경과 사업장명 Snapshot은 수정하지 않습니다.
- 성공은 Body 없는 204이고 최신 상세은 GET으로 다시 조회합니다.

### `DELETE /api/work-cases/{workCaseId}`

- 해당 OWNER만 호출합니다.
- `DRAFT`가 아니거나 계약·에스크로가 있으면 `409 WORK_CASE_LOCKED`입니다.
- 초대 이력이 없으면 Hard Delete하고, 있으면 활성 `PENDING` 초대를 철회한 뒤 `CANCELED`로
  전이합니다. 두 성공 경로 모두 Body 없는 204입니다.

### `POST /api/work-cases/{workCaseId}/invitations`

- 해당 OWNER만 호출하며 Target Body는 `{healthCertificateRequired: boolean}`입니다. 이 값은
  초대 조건 Snapshot에 고정되며 일반 발급 재조회와 재발급 응답에도 같은 값이 포함됩니다.
- Work Case가 `DRAFT`, `workerId=null`, `now < startsAt`일 때만 발급합니다.
- Work Case와 활성 초대를 순서대로 잠급니다. 만료된 `PENDING`은 먼저 `EXPIRED`로
  전이합니다.
- 현재 조건 Version의 유효한 `PENDING`이 없으면 새 초대를 만들고 201, 있으면 행과 만료를
  바꾸지 않고 같은 응답을 200으로 반환합니다.
- `inviteUrl`은 요청 Header가 아니라 배포 설정의 허용 Web Origin과
  `/invitations/{token}`을 결합한 Query·Fragment 없는 절대 URL입니다.

```json
{
  "data": {
    "inviteUrl": "https://app.example.com/invitations/BASE64URL_TOKEN",
    "expiresAt": "2026-08-20T01:00:00Z",
    "healthCertificateRequired": true
  }
}
```

### `POST /api/work-cases/{workCaseId}/invitations/reissue`

- 해당 OWNER만 호출하며 Body는 없습니다.
- 현재 조건 Version의 유효한 `PENDING`이 없으면 `409 CONFLICT`입니다.
- Work Case와 현재 초대를 잠근 뒤 기존 초대를 `REVOKED`로 바꾸고 다른 ID·Token의 새
  `PENDING`을 한 트랜잭션에서 만듭니다. 성공은 발급과 같은 Envelope의 201입니다.
- 재발급 전 Link는 즉시 `409 INVITATION_REVOKED`가 됩니다.
- `Idempotency-Key` Replay를 적용하지 않습니다. 동시 요청은 요청마다 Link를 교체하며 마지막
  Link만 유효합니다. 성공 응답이 유실되면 자동 재시도하지 않고 일반 발급으로 현재 Link를
  복구합니다.

### Capability와 화면 파생

응답에 `canEdit`, `canDelete`, `canIssueInvitation` 또는 같은 의미의 별칭을 추가하지 않습니다.

- 수정·삭제는 `status == DRAFT`일 때 표시합니다.
- 발급·현재 Link 복사는 `status == DRAFT`, `worker == null`, `now < startsAt`일 때
  표시합니다.
- 재발급은 위 조건과 `latestInvitation.status == PENDING`,
  `latestInvitation.termsVersion == termsVersion`을 모두 만족할 때 표시합니다.
- 서버는 모든 변경 요청에서 권한, 상태, 매칭, 시각과 초대 Version을 다시 검증합니다.

## 초대 조회와 수락

### `GET /api/invitations/{token}`

- 인증된 WORKER만 호출합니다. 비인증 API는 `401 AUTH_REQUIRED`, 다른 역할은
  `403 ROLE_MISMATCH`입니다.
- 비로그인 웹 접근은 `/worker/login?redirect={encodedInvitationPath}`로 보낸 뒤 원래 경로로
  복귀합니다.
- 초대는 Bearer이므로 사전 대상 사용자 ID를 검사하지 않습니다. Token Hash로 찾은 초대의
  상태, `now < expiresAt`, 현재 `termsVersion`과 Work Case의 수락 가능 상태를 검증합니다.
- `now >= expiresAt`인 `PENDING`은 조회 트랜잭션에서 `EXPIRED`로 전이하고
  `410 INVITATION_EXPIRED`를 반환합니다.
- 인증과 검증 전에 초대 내용과 OWNER Badge를 노출하지 않습니다. 시각은 UTC `Instant`,
  금액은 KRW 원 단위 정수입니다.

```json
{
  "data": {
    "title": "주말 홀 서빙",
    "workplaceName": "강남점",
    "startsAt": "2026-08-20T01:00:00Z",
    "endsAt": "2026-08-20T09:00:00Z",
    "breakMinutes": 60,
    "breakPaid": false,
    "dailyWage": 120000,
    "healthCertificateRequired": true,
    "expiresAt": "2026-08-20T01:00:00Z",
    "ownerBadge": {
      "badgeType": "TRUST_OWNER",
      "level": 2
    }
  }
}
```

OWNER의 누적 이력을 같은 요청에서 재계산한 결과가 1~3단계이면 `ownerBadge`에
`badgeType=TRUST_OWNER`와 `level`만 반환하고, 0단계이면 `"ownerBadge": null`을 반환합니다.
내부 `termsVersion`, 초대 ID, Token Hash와 문서 Storage Key는 이 응답에 포함하지 않습니다.

### 초대 오류 응답

| 상황                                      | HTTP | Code                          | Message                                           |
| ----------------------------------------- | ---: | ----------------------------- | ------------------------------------------------- |
| Token 형식 오류 또는 미존재               |  404 | `RESOURCE_NOT_FOUND`          | `초대 링크를 찾을 수 없습니다.`                   |
| 인증 없음                                 |  401 | `AUTH_REQUIRED`               | 공통 인증 메시지                                  |
| WORKER 역할이 아님                        |  403 | `ROLE_MISMATCH`               | 공통 역할 불일치 메시지                           |
| 만료                                      |  410 | `INVITATION_EXPIRED`          | `초대 링크가 만료되었습니다.`                     |
| 철회                                      |  409 | `INVITATION_REVOKED`          | `철회된 초대 링크입니다.`                         |
| 수락 완료                                 |  409 | `INVITATION_ALREADY_ACCEPTED` | `이미 수락된 초대 링크입니다.`                    |
| `PENDING`이지만 조건 Version 불일치       |  409 | `INVITATION_TERMS_CHANGED`    | `근무 조건이 변경되어 초대를 사용할 수 없습니다.` |
| 발급할 수 없는 Work Case 상태·시각        |  409 | `WORK_CASE_LOCKED`            | `초대를 발급할 수 없는 근무입니다.`               |
| 활성 초대가 없는 재발급 등 기타 상태 충돌 |  409 | `CONFLICT`                    | `초대 상태를 다시 확인해 주세요.`                 |

오류 Body는 공통 `{code,message,traceId,fieldErrors?}` Envelope를 사용합니다.

### `POST /api/invitations/{token}/accept`

- 인증된 WORKER만 호출합니다. 인증 없음은 `401 AUTH_REQUIRED`, 다른 역할은
  `403 ROLE_MISMATCH`입니다.
- CSRF와 `Idempotency-Key` 공통 규칙을 적용합니다.
- HTTP Body는 0byte여야 합니다. JSON `{}`, `null`, 공백, 사용자·근무·금액 ID, 이름,
  서명 이미지와 Multipart를 포함한 모든 Body는 `400 VALIDATION_ERROR`입니다.
- Token을 Base64url 형식으로 해석하고 Hash로 상태와 무관한 초대 행을 조회합니다. 형식
  오류·미존재는 Claim 없이 `404 RESOURCE_NOT_FOUND`입니다.
- Token, Session·CSRF, Idempotency-Key, 이름, 계약 Bytes와 저장 Key를 일반 로그·분석
  이벤트·오류에 남기지 않습니다.

Claim과 Replay 순서는 다음과 같습니다.

1. HTTP 구조·인증·역할·Body·Key와 Token 존재를 검증합니다.
2. Token Hash와 `expected_terms_version`으로 `INVITATION_ACCEPT` Fingerprint를 만듭니다.
3. 멱등 Claim을 선점합니다.
4. 같은 Fingerprint의 완료 Claim이면 저장한 성공 Body와 200을
   `Idempotency-Replayed: true`로 반환합니다.
5. 새 Claim만 Aggregate Transaction에 진입합니다.

Aggregate Transaction은 다음 순서로 처리합니다. 검증 실패는 성공 Claim을 남기지 않으며,
만료 전이처럼 보존할 변경이 있으면 그 변경과 Claim 삭제만 Commit한 뒤 오류를 반환합니다.

1. Claim, Work Case, Invitation, OWNER Wallet 순서로 잠급니다.
2. Token Hash와 관계를 다시 확인하고 인증 WORKER가 OWNER와 같으면 `403 FORBIDDEN`입니다.
3. Invitation의 `ACCEPTED`, `REVOKED`, `EXPIRED`는 각각
   `INVITATION_ALREADY_ACCEPTED`, `INVITATION_REVOKED`, `INVITATION_EXPIRED`입니다.
4. `PENDING`이지만 `now >= expires_at`이면 `EXPIRED`로 전이하고 410을 반환합니다.
5. `expected_terms_version != work_cases.terms_version`이면
   `409 INVITATION_TERMS_CHANGED`입니다.
6. Work Case가 `DRAFT`가 아니거나 이미 WORKER가 있거나 시작 시각이 지났으면
   `409 WORK_CASE_LOCKED`입니다. 계약·문서·에스크로·Settlement가 일부만 존재하는 손상
   상태는 `500 INTERNAL_ERROR`입니다.
7. OWNER KRW Wallet의 `available_balance < agreed_wage`이면 잔액을 노출하지 않고
   `409 CONFLICT`와 `사장님의 예치 가능 잔액이 부족하여 근무를 확정할 수 없습니다.`를
   반환합니다.
8. 하나의 `acceptedAt`으로 아래 DB Aggregate와 임시 PDF를 만듭니다.
9. 성공 Body와 200 상태로 Claim을 `COMPLETED` 전이하고 한 번에 Commit합니다.
10. Commit 뒤 PDF 승격을 시도하며 실패해도 성공 응답을 실패로 바꾸지 않습니다.

성공 시 DB 변경은 다음과 같습니다.

- `work_cases`: `worker_id=Principal.userId`, `DRAFT → ACCEPTED`.
- `work_invitations`: `PENDING → ACCEPTED`, `accepted_by_user_id`,
  `accepted_terms_version`, `accepted_at` 기록.
- `work_contracts`: 당사자, 제목, 시각, 휴게·유급, 사업장 Snapshot, 일급,
  `source_terms_version`, `accepted_at`을 Work Case당 한 행에 저장합니다.
- `work_contracts.terms_snapshot`: 아래 닫힌 JSON Shape를 저장합니다.

```json
{
  "schemaVersion": 1,
  "termsVersion": 3,
  "title": "주말 홀 서빙",
  "startsAt": "2026-08-20T01:00:00Z",
  "endsAt": "2026-08-20T09:00:00Z",
  "breakMinutes": 60,
  "breakPaid": false,
  "workplaceName": "강남점",
  "workplaceAddress": "서울특별시 강남구 테헤란로 1 2층",
  "workplaceLatitude": 37.498,
  "workplaceLongitude": 127.027,
  "allowedRadiusMeters": 100,
  "dailyWage": 120000,
  "owner": {
    "userId": 7,
    "name": "김사장"
  },
  "worker": {
    "userId": 42,
    "name": "이알바"
  }
}
```

- `documents`: Work Case당 `EMPLOYMENT_CONTRACT` 한 행입니다. 작성자·소유자는 OWNER,
  `issued_on`은 `acceptedAt`의 `Asia/Seoul` 날짜, `expires_on=null`입니다. Version 1 준비 시
  `AWAITING_SIGNATURE`, Version 2·서명·공유 준비 시 `SIGNED`, 최종 `ACTIVE`로 전이하고
  Transaction 내부 중간 상태는 외부에 노출하지 않습니다.
- `document_versions`: `ORIGINAL` Version 1과 `SIGNED` Version 2 PDF의 최종 Key, MIME,
  byte 크기와 SHA-256을 기록합니다.
- `document_signatures`: WORKER의 `TYPED_NAME` 한 행에 Source·Signed Version, 각 Checksum,
  수락 당시 이름과 동일한 동의·서명 시각을 기록합니다. OWNER 서명 행은 만들지 않습니다.
- `document_shares`: WORKER에게 `CONTRACT_PARTY`, `ACTIVE`, `expires_at=null` 한 행입니다.
- OWNER Wallet: `available_balance -= agreed_wage`, `locked_balance += agreed_wage`.
- `escrows`: Work Case당 `amount=agreed_wage`, `status=HELD`, `held_at=acceptedAt` 한 행입니다.
- `wallet_transactions`: OWNER Wallet에 `ESCROW_HOLD`와 잔액 전후 Snapshot,
  `reference_type=ESCROW`, `reference_id=escrow.id`를 기록합니다. 내부 `idempotency_key`는 Raw
  Header가 아니라 `EHLD:`와
  `SHA-256(UTF-8("INVITATION_ACCEPT\n" + decimalClaimId))` 소문자 Hex의 결합입니다.
- `settlements`: `amount=agreed_wage`, `status=WAITING`, `due_at=null`, 승인·처리·완료·실패
  필드가 `null`인 Work Case당 한 행입니다.
- WORKER Wallet과 WORKER 원장은 수락 시 바꾸지 않고 M6 에스크로 해제에서 생성합니다.

최초 성공은 200이며 `Idempotency-Replayed` Header를 생략합니다.

```json
{
  "data": {
    "workCaseId": 123,
    "escrowStatus": "HELD"
  }
}
```

24시간 보존 안의 같은 Key·Fingerprint Replay는 정확히 같은 Body와 200을 반환하고
`Idempotency-Replayed: true`를 설정합니다. 새 Aggregate를 만들거나 현재 Token·조건·잔액·
파일 승격 상태를 다시 검사하지 않습니다.

수락 오류는 다음과 같습니다.

| 상황                                               | HTTP | Code                          |
| -------------------------------------------------- | ---: | ----------------------------- |
| Token 형식 오류·미존재                             |  404 | `RESOURCE_NOT_FOUND`          |
| 인증 없음                                          |  401 | `AUTH_REQUIRED`               |
| WORKER 역할이 아님                                 |  403 | `ROLE_MISMATCH`               |
| Work Case OWNER와 같은 사용자                      |  403 | `FORBIDDEN`                   |
| 같은 Key와 다른 Token·조건 Version                 |  409 | `IDEMPOTENCY_KEY_REUSED`      |
| 같은 Key·Fingerprint의 처리 중                     |  409 | `CONFLICT`                    |
| 만료                                               |  410 | `INVITATION_EXPIRED`          |
| 철회                                               |  409 | `INVITATION_REVOKED`          |
| 이미 수락 또는 동시 수락 패배                      |  409 | `INVITATION_ALREADY_ACCEPTED` |
| 초대와 현재 조건 Version 불일치                    |  409 | `INVITATION_TERMS_CHANGED`    |
| 수락 불가 Work Case 상태·매칭·시각                 |  409 | `WORK_CASE_LOCKED`            |
| OWNER 예치 가능 잔액 부족                          |  409 | `CONFLICT`                    |
| 부분 Aggregate, 파일 무결성 또는 예상 밖 서버 오류 |  500 | `INTERNAL_ERROR`              |

잠금 교착이나 일시 충돌로 본 Transaction이 Commit되지 않았으면 Claim을 삭제하고 승인된
`409 CONFLICT`로 반환해 같은 Key 재시도를 허용합니다. Commit 여부가 불명확하면 Key를 바꾸지
않고 Replay합니다.

## 정산 승인·자동 실행과 임금분쟁

### Settlement 상태·시간·자금 경계

- 초대 수락 Aggregate는 합의 일급의 Settlement를 `WAITING`, `due_at=null`로 만듭니다.
- 정상 또는 확인된 CHECK_OUT은 Work Case를 `COMPLETED`, Settlement를 `SCHEDULED`,
  `due_at=recordedAt+24시간`으로 한 Transaction에서 바꿉니다. 정산 처리기는 이미 완료된
  Work Case 상태를 만들거나 변경하지 않습니다.
- `due_at`은 OWNER 승인의 만료 시각이 아니라 Scheduler가 추가로 지급 자격을 얻는 경계입니다.
  OWNER는 Scheduler가 선점하기 전까지 `due_at` 전후 모두 승인할 수 있습니다.
- Scheduler의 시간 판정은 MySQL `NOW(6)`을 사용하고 `due_at <= NOW(6)`을 포함합니다. API
  시각은 UTC `Instant`, DB `DATETIME(6)`은 `Asia/Seoul` 벽시계입니다.
- 정상 지급은 `PROCESSING`, Escrow 해제, OWNER locked 감소, WORKER available 증가, 양측
  `ESCROW_RELEASE` 원장과 `COMPLETED`를 하나의 Transaction에서 확정합니다. 실패하면 모두
  Rollback되어 `SCHEDULED`로 남고 `PROCESSING`은 별도 Commit하지 않습니다.

| 현재 상태 | 허용 전이 | 의미 |
| --- | --- | --- |
| `WAITING` | `SCHEDULED`, `PROCESSING` | 정상 CHECK_OUT 예약 또는 NO_SHOW 환불 승인 대기 |
| `SCHEDULED` | `ON_HOLD`, `PROCESSING` | OWNER 또는 due Scheduler가 지급할 수 있는 정상 정산 |
| `ON_HOLD` | `SCHEDULED` | 열린 임금분쟁으로 정상 지급이 보류됨; `due_at` 보존 |
| `PROCESSING` | `COMPLETED`, `REFUNDED` | 같은 자금 Transaction 안에서만 존재하는 중간 상태 |
| `COMPLETED` | 없음 | 정상·지각 지급이 끝난 재처리 불가 상태 |
| `REFUNDED` | 없음 | NO_SHOW 전액 환불이 끝난 재처리 불가 상태 |
| `FAILED` | 관리자 승인 복구만 | 자동 지급 재시도 소진 또는 무결성 실패로 자동 재처리 금지 |

`CHECK_OUT_MISSING`은 추가 결정이 승인될 때까지 `WAITING/due_at=null`, Escrow `HELD`를
유지하며 지급·환불하지 않습니다.

### `POST /api/work-cases/{workCaseId}/settlement/approve`

- 해당 Work Case의 OWNER만 호출하며 Body는 0byte, CSRF와 `Idempotency-Key`가 필수입니다.
- Work Case `COMPLETED`, Settlement `SCHEDULED`, Escrow `HELD`, 열린 분쟁 없음과 당사자·
  합의 금액을 잠금 뒤 다시 검증합니다.
- Operation은 `SETTLEMENT_APPROVE`이고 Fingerprint는 공통 멱등 계약을 따릅니다. OWNER 승인과
  Scheduler는 Settlement ID 기반 `SETTLEMENT_RELEASE_OWNER`, `SETTLEMENT_RELEASE_WORKER`
  Namespace의 SHA-256인 같은 결정적 원장 Key와 원자 지급 실행기를 사용합니다.
  `wallet_transactions.idempotency_key`의 Unique가 상태 전이와 함께 중복 자금 이동을 최종
  방어합니다.

```json
{
  "data": {
    "settlementId": 1,
    "status": "COMPLETED",
    "originalEscrowAmount": 120000,
    "workerPaidAmount": 120000,
    "ownerRefundAmount": 0,
    "completedAt": "2026-07-31T09:00:00Z"
  }
}
```

`completedAt`은 UTC `Instant`입니다. 최초 성공과 같은 Key·Fingerprint Replay 모두 200이며
Replay에는 `Idempotency-Replayed: true`를 설정합니다. 정상·지각 근무 모두 WORKER에게 원
예치액인 약정 일급 전액을 지급하고 OWNER 환불은 0원입니다. `lateMinutes`는 근태 정보이며 현재
MVP의 지급액 입력으로 사용하지 않습니다. 다른 Key 또는
Scheduler가 먼저 완료한 정산은 새 성공으로 바꾸지 않고 `409 SETTLEMENT_ALREADY_PROCESSED`로
응답합니다.

### `POST /api/work-cases/{workCaseId}/settlement/no-show-refund/approve`

- 해당 Work Case의 OWNER만 호출하며 Body는 0byte, CSRF와 `Idempotency-Key`가 필수입니다.
- 성공 CHECK_IN이 없고 Work Case `NO_SHOW`, Settlement `WAITING/due_at=null`, Escrow `HELD`,
  열린 분쟁 없음인 경우만 승인합니다. 시스템 NO_SHOW 판정은 상태만 바꾸며 자동 환불하지
  않습니다.
- Operation은 `SETTLEMENT_NO_SHOW_REFUND_APPROVE`이고 Fingerprint는 공통 멱등 계약을
  따릅니다. 원장 Key는 Settlement ID 기반 `SETTLEMENT_REFUND_OWNER` Namespace의 SHA-256으로
  정합니다.
- 성공은 Settlement `PROCESSING → REFUNDED`, Escrow `HELD → REFUNDED`, OWNER locked 감소·
  available 증가와 OWNER `ESCROW_REFUND` 한 건을 한 Transaction으로 확정합니다. WORKER
  Wallet과 원장은 바꾸지 않습니다.

```json
{
  "data": {
    "settlementId": 3,
    "status": "REFUNDED",
    "originalEscrowAmount": 120000,
    "workerPaidAmount": 0,
    "ownerRefundAmount": 120000,
    "completedAt": "2026-08-20T02:05:00Z"
  }
}
```

최초 성공과 같은 Key·Fingerprint Replay는 200이며 Replay Header를 반환합니다. 열린 분쟁은
`409 SETTLEMENT_ON_HOLD`, 다른 Key로 이미 처리된 환불은
`409 SETTLEMENT_ALREADY_PROCESSED`입니다.

### 예정 자동 지급

- 외부 실행 Endpoint나 임의 OWNER 승인자를 만들지 않습니다. 자동 지급의
  `approved_by_user_id`는 `null`, 내부 Operation 식별자는 Settlement ID 기반
  `SETTLEMENT_SCHEDULED_PAYOUT`입니다.
- 한 실행은 `SCHEDULED`, `due_at <= NOW(6)`, 열린 분쟁 없음,
  `next_retry_at IS NULL OR next_retry_at <= NOW(6)` 후보를 `due_at ASC, id ASC` 순서로 최대
  100건 처리합니다. 각 후보는 짧은 독립 Transaction에서 `FOR UPDATE SKIP LOCKED`와 조건부
  전이로 선점합니다.
- Deadlock, Lock Timeout과 일시 Adapter 실패는 자금 Transaction 전체를 Rollback한 뒤 별도
  짧은 감사 Transaction에서 `retry_count`, `failure_code`, `last_failure_at`, `next_retry_at`을
  갱신합니다. 재시도 간격은 1분, 5분, 15분, 60분이고 이후 60분을 유지합니다.
- 총 다섯 번 실패하면 돈과 원장이 움직이지 않았음을 재검증하고 `FAILED`로 전이합니다. 상태·
  금액·원장 무결성 실패도 즉시 `FAILED`와 닫힌 `failure_code`를 남기며 자동 정상화하지
  않습니다. 프로세스 중단은 `SCHEDULED`로 Rollback되고 과거 고착 `PROCESSING`은 자동
  지급하지 않고 수동 대사 대상으로 격리합니다.

### `POST /api/work-cases/{workCaseId}/disputes`

- Work Case OWNER와 배정 WORKER만 호출하며 CSRF가 필수입니다. 서버는
  `dispute_type=WAGE`로 기록하고 Work Case당 `OPEN`·`UNDER_REVIEW` 분쟁을 하나만 허용합니다.
- Body의 `title`은 trim 후 1~100자, `content`는 trim 후 1~2000자입니다.

```json
{
  "title": "지급 금액 확인 요청",
  "content": "지각 차감 내역을 확인해 주세요."
}
```

성공은 `201 {"data":{"reportId":1}}`입니다. 정상 `SCHEDULED` 정산에 분쟁을 등록하면 같은
Transaction에서 `ON_HOLD`로 바꾸되 `due_at`, Escrow, Wallet과 원장은 보존합니다. NO_SHOW의
`WAITING` 상태는 그대로 두지만 열린 분쟁이 환불 승인을 막습니다. 분쟁 등록과 지급은
`work_cases → settlements → disputes` 순서로 직렬화하며 지급이 먼저 Commit된 경우 후속
신고가 이미 끝난 자금 이동을 되돌리지 않습니다.

### `GET /api/work-cases/{workCaseId}/disputes`

- Work Case OWNER와 배정 WORKER만 공통 Page Query로 조회합니다.
- Item은 `reportId`, `title`, `content`, `status`, nullable `resolution`, `requesterRole`,
  `createdAt`, nullable `resolvedAt`을 반환하고 내부 사용자 ID와 처리자 ID를 노출하지 않습니다.

`OPEN`, `UNDER_REVIEW`는 열린 분쟁이며 정상 지급과 NO_SHOW 환불을 모두 막습니다.
`RESOLVED`, `REJECTED`, `CANCELED`은 닫힌 분쟁입니다. 마지막 열린 분쟁을 닫는 Transaction은
정상 Settlement를 `ON_HOLD → SCHEDULED`로 복구하고 기존 `due_at`을 보존하며, NO_SHOW 환불
승인도 다시 허용합니다. 관리자 역할과 상태 변경 Endpoint는 `DEC-OPEN-ADMIN-DISPUTE`가
닫힐 때까지 제공하지 않습니다.

### 정산·분쟁 오류

| 상황 | HTTP | Code |
| --- | ---: | --- |
| 인증 없음 | 401 | `AUTH_REQUIRED` |
| OWNER 역할 불일치 | 403 | `ROLE_MISMATCH` |
| 다른 OWNER의 Work Case 또는 존재하지 않는 Work Case | 404 | `RESOURCE_NOT_FOUND` |
| 열린 분쟁으로 지급·환불 보류 | 409 | `SETTLEMENT_ON_HOLD` |
| Work·Settlement·Escrow가 승인 가능한 상태가 아님 | 409 | `SETTLEMENT_NOT_READY` |
| 다른 Key 또는 Scheduler가 이미 처리함 | 409 | `SETTLEMENT_ALREADY_PROCESSED` |
| 같은 Key가 같은 Fingerprint를 처리 중 | 409 | `CONFLICT` |
| 같은 Key를 다른 Fingerprint에 재사용 | 409 | `IDEMPOTENCY_KEY_REUSED` |
| 열린 분쟁 중복 등록 | 409 | `DISPUTE_ALREADY_OPEN` |
| 제한 재시도 뒤 일시 장애 지속 | 503 | `SETTLEMENT_TEMPORARILY_UNAVAILABLE` |
| 상태·금액·원장 무결성 모순 | 500 | `INTERNAL_ERROR` |

## 사업장 고정 QR과 근태

QR은 사업장별로 활성 Token이 정확히 하나인 비만료 고정 QR입니다. 사업장 등록 성공 시 같은
트랜잭션에서 최초 QR을 발급하며 조회는 QR을 만들거나 교체하지 않습니다. `ACTIVE` 사업장에
활성 QR이 없는 상태는 저장소 무결성 오류이며 재발급은 기존 활성 QR이 없어도 복구할 수
있습니다.

Token은 다음 다섯 세그먼트를 `.`으로 연결합니다.

```text
v1.k1.42.AQIDBAUGBwgJCgsMDQ4PEA.qZ7XcO1nB2sV4hK9pR0tYuI3wE5aM6dF7gH8jL2xN0c
```

| 순서 | 내용                                                                           |
| ---: | ------------------------------------------------------------------------------ |
|    1 | 고정 문자열 `v1`                                                               |
|    2 | 서명 키 식별자. `[A-Za-z0-9_-]{1,16}`                                          |
|    3 | 사업장 식별자의 10진 표기                                                      |
|    4 | 16byte nonce를 Padding 없는 Base64 URL-safe로 인코딩한 값                      |
|    5 | 앞 네 세그먼트 전체의 HMAC-SHA256을 Padding 없는 Base64 URL-safe로 인코딩한 값 |

검증은 다섯 세그먼트의 구조와 MAC이 모두 일치할 때만 성공합니다. 키 식별자를 Token에 담아
키 교체 기간에도 현장에 인쇄된 QR이 참조하는 구 키를 검증 집합에 유지할 수 있게 합니다.

- HMAC Key는 저장소와 WAR에 포함하지 않고 외부 Properties로 주입하며 필수 Key가 없으면
  애플리케이션 기동에 실패합니다.
- DB에는 공개 식별자인 nonce만 저장합니다. HMAC Key와 완성 Token 원문은 DB, 일반 로그와
  분석에 저장하지 않습니다.
- 서명 검증은 Token이 등록된 Key로 위조되지 않았는지만 판정합니다. 폐기된 nonce인지는
  실제 스캔이 DB의 현재 QR 상태를 확인해 거절합니다.
- QR을 만들거나 바꾸는 흐름은 `workplaces`를 먼저 잠그고 `qr_tokens`를 처리합니다.

### `GET /api/workplaces/{workplaceId}/qr`

- 해당 사업장 OWNER만 호출합니다.
- 조회는 QR을 생성하거나 교체하지 않습니다.
- 같은 사업장을 반복 조회하면 항상 같은 Token 문자열을 반환합니다.

```json
{
  "data": {
    "workplaceId": 42,
    "qrToken": "v1.k1.42.AQIDBAUGBwgJCgsMDQ4PEA.qZ7XcO1nB2sV4hK9pR0tYuI3wE5aM6dF7gH8jL2xN0c",
    "createdAt": "2026-07-31T00:00:00Z"
  }
}
```

### `POST /api/workplaces/{workplaceId}/qr/reissue`

- 해당 사업장 OWNER만 호출합니다.
- 사업장 행을 잠근 뒤 기존 활성 QR을 `REVOKED`와 폐기 시각으로 전이하고 새 nonce를
  발급합니다. 기존 활성 QR이 없어도 새 QR 발급은 성공합니다.
- 동시 요청이 겹쳐도 활성 QR은 하나만 남으며 응답 Token은 해당 요청이 저장한 nonce로
  서명합니다.

```json
{
  "data": {
    "workplaceId": 42,
    "qrToken": "v1.k1.42.ERITFBUWFxgZGhscHR4fIA.8eVvD4xP4TjV7N2cZ0aWq9sLk3mH6fY1bR5uC2iQ7oA",
    "reissuedAt": "2026-07-31T00:10:00Z"
  }
}
```

재발급은 `Idempotency-Key` Header를 요구하지 않습니다. 응답을 확인하지 못한 클라이언트는
POST를 자동 반복하지 않고 `GET /api/workplaces/{workplaceId}/qr`로 현재 활성 QR을
복구합니다. 사용자가 다시 확인한 재발급만 새로운 회전 의도입니다.

재발급과 스캔은 모두 `workplaces`를 먼저 잠그고 활성 `qr_tokens`를 확인하며 스캔은 그 뒤
`work_cases`를 잠급니다. 사업장 잠금을 먼저 얻은 Transaction이 이깁니다. 재발급이 먼저
Commit되면 구 nonce 스캔은 `410 QR_REVOKED`, 스캔이 현재 nonce 검증과 근태 Commit을 먼저
끝내면 그 근태는 유효하고 뒤이은 재발급은 이후 스캔에만 적용합니다.

고정 QR 조회·재발급 오류는 다음과 같습니다.

| 상황                                     | HTTP | Code                 |
| ---------------------------------------- | ---: | -------------------- |
| 인증 없음                                |  401 | `AUTH_REQUIRED`      |
| OWNER 역할이 아님                        |  403 | `ROLE_MISMATCH`      |
| 없는 사업장 또는 다른 OWNER의 사업장     |  404 | `RESOURCE_NOT_FOUND` |
| `ACTIVE` 사업장에 활성 QR 없음(조회)     |  500 | `INTERNAL_ERROR`     |
| 동시 재발급 경쟁에서 활성 QR 유일성 충돌 |  409 | `CONFLICT`           |

없는 사업장과 다른 OWNER의 사업장을 구분하지 않습니다. 조회의 무결성 오류는 공통 안전 메시지와
`traceId`를 반환하며 내부 상태나 Token 원문을 노출하지 않습니다.

### 근태 자동 상태 판정

서버 Scheduler는 Job 시작 시 한 번 얻은 시각으로 다음 경계를 판정하며 정상 운영 중 기준
시각부터 1분 안에 전이합니다.

| 경계                       | 값                  | 포함 규칙                            |
| -------------------------- | ------------------- | ------------------------------------ |
| READY 시작                 | `starts_at - 30분`  | 해당 시각 포함                       |
| CHECK_IN 종료·NO_SHOW 판정 | `starts_at + 1시간` | CHECK_IN은 미포함, NO_SHOW는 포함    |
| CHECK_OUT 종료·누락 판정   | `ends_at + 2시간`   | CHECK_OUT은 미포함, 누락 판정은 포함 |

`ACCEPTED`는 배정 WORKER·수락 초대·계약 조건 Version, 읽을 수 있고 Checksum이 일치하는
최종 SIGNED 계약, 같은 일급의 `HELD` Escrow, `WAITING/due_at=null` Settlement, ACTIVE
사업장과 현재 좌표가 모두 있을 때만 READY가 됩니다. 누락 조건이 CHECK_IN 종료 전 충족되면
다음 주기에 READY가 될 수 있고, 끝까지 불완전하면 시스템 준비 실패이므로 `ACCEPTED`에 남아
WORKER의 NO_SHOW로 만들지 않습니다.

성공 CHECK_IN이 없는 READY만 CHECK_IN 종료 경계에 `NO_SHOW`, 성공 CHECK_IN이 있고 성공
CHECK_OUT이 없는 IN_PROGRESS만 CHECK_OUT 종료 경계에 `CHECK_OUT_MISSING`이 됩니다. 스캔과
Scheduler는 Work Case 잠금과 조건부 상태 변경으로 한 결과만 Commit합니다. M5는 두 종료
상태의 늦은 QR·수동 보정 API를 제공하지 않고 Settlement를 `WAITING/due_at=null`로 유지하며
Wallet·Escrow 금액과 원장을 바꾸지 않습니다.

### `POST /api/attendance/scans`

인증된 WORKER가 공통 형식의 `Idempotency-Key` Header와 다음 Body로 호출합니다.

```json
{
  "qrToken": "signed-token",
  "latitude": 37.1234567,
  "longitude": 127.1234567,
  "accuracyMeters": 18.25,
  "capturedAt": "2026-08-07T01:00:00Z",
  "confirmEarlyCheckout": false
}
```

- 위도·경도는 소수점 7자리 이하, 정확도는 0 이상 100 이하의 소수점 2자리 이하입니다.
- `capturedAt`은 서버 수신 시각보다 정확히 5분 전부터 1분 후까지 포함합니다.
- `attempted_at`은 요청마다 정한 서버 판정 시각, `captured_at`은 브라우저 측정 시각,
  `recordedAt`은 성공 `attempted_at`입니다.
- 거리는 현재 `workplaces` 좌표와 지구 반지름 6,371,000m의 Haversine Double 계산을 사용하고
  반올림 전 100m를 포함합니다. 판정 뒤 거리만 소수점 2자리로 저장합니다.
- WORKER 원문 위도·경도는 판정 중 메모리에서만 사용하고 DB·Claim·일반 로그에 보존하지
  않습니다. 근태 행에는 거리, 정확도와 측정·시도 시각만 남깁니다.

현재 QR 사업장과 인증 WORKER를 기준으로 다음 활성 후보를 함께 조회합니다.

- `READY`이고 `starts_at - 30분 <= attemptedAt < starts_at + 1시간`이면 CHECK_IN
- 성공 CHECK_IN이 있는 `IN_PROGRESS`이고 `attemptedAt < ends_at + 2시간`이면 CHECK_OUT

활성 후보가 0건이면 완료 후보를 같은 시간 범위에서 확인합니다. 완료 후보 한 건은
`409 ATTENDANCE_ALREADY_COMPLETED`, 두 건 이상 또는 활성 후보 복수는
`409 ATTENDANCE_WORK_CASE_AMBIGUOUS`, 아무 후보도 없으면
`404 ATTENDANCE_WORK_CASE_NOT_FOUND`입니다. 서버는 시각이나 ID로 임의 선택하지 않으며
`NO_SHOW`, `CHECK_OUT_MISSING`은 늦은 스캔 후보가 아닙니다.

같은 스캔 응답 유실은 같은 Key와 Body로 재시도하고 실제 CHECK_OUT과 조기 퇴근 확인은 각각
새 Key를 사용합니다. 성공 기록과 `CONFIRMATION_REQUIRED`는 24시간 저장해 현재 상태 판정 전에
200 Replay하며 `Idempotency-Replayed: true`를 붙입니다. 본 처리 실패 Claim은 삭제합니다.

본 처리는 `workplaces → qr_tokens → work_cases` 순서로 잠급니다. 서로 다른 Key가 같은
출퇴근 슬롯을 경쟁하면 먼저 Commit한 요청만 성공하고 패자와 성공 슬롯 Unique 충돌은
`409 ATTENDANCE_STATE_CONFLICT`입니다. Deadlock·Lock Timeout은 같은 의도를 최대 2회 내부
재시도해 총 3회 시도하고 계속 실패하면 Claim을 제거한 뒤
`503 ATTENDANCE_TEMPORARILY_UNAVAILABLE`를 반환합니다.

CHECK_IN의 `attemptedAt > starts_at`이면 지각이며 `lateMinutes`는 양의 차이를 분 단위로
올림합니다. 지각은 저장 Work Case 상태가 아닙니다.

일반 성공은 다음 필드를 항상 반환합니다. CHECK_IN의 `settlementDueAt`과 확인되지 않은
`earlyCheckoutConfirmedAt`은 `null`입니다.

```json
{
  "data": {
    "result": "RECORDED",
    "workCaseId": 123,
    "scanType": "CHECK_IN",
    "recordedAt": "2026-08-07T01:00:05Z",
    "isLate": true,
    "lateMinutes": 1,
    "earlyCheckoutConfirmedAt": null,
    "settlementDueAt": null
  }
}
```

예정 종료 전 CHECK_OUT의 `confirmEarlyCheckout:false` 요청은 성공·거절 근태 행과 상태 변경
없이 200으로 다음 결과를 반환합니다.

```json
{
  "data": {
    "result": "CONFIRMATION_REQUIRED",
    "workCaseId": 123,
    "scanType": "CHECK_OUT",
    "scheduledEndAt": "2026-08-07T09:00:00Z"
  }
}
```

확인은 같은 QR·위치 계약을 다시 검증하는 `confirmEarlyCheckout:true`의 새 의도입니다. 정상
또는 확인된 CHECK_OUT은 성공 행, `IN_PROGRESS→COMPLETED`, Settlement
`WAITING→SCHEDULED`, `due_at=recordedAt+24시간`을 한 트랜잭션에서 반영합니다. M6는 이
예약을 소비해 실제 자금만 이동합니다.

정확히 하나의 Work Case와 출퇴근 유형을 정한 뒤 발생한 의미상 위치·시간·상태 거부는
`attendance_records.result=REJECTED`와 `LOCATION_INACCURATE`, `LOCATION_STALE`,
`OUTSIDE_RADIUS`, `TIME_WINDOW_CLOSED`, `STATE_CONFLICT` 중 하나를 남깁니다. QR 변조와 후보
없음·복수처럼 신뢰할 Work Case를 정할 수 없는 요청은 근태 행 없이 민감값을 제외한 구조화
보안 로그와 `traceId`만 남깁니다.

| 상황                                          | HTTP | Code                                 |
| --------------------------------------------- | ---: | ------------------------------------ |
| QR 형식·Key ID·HMAC·사업장 식별 실패          |  422 | `QR_INVALID`                         |
| 서명은 유효하지만 현재 QR이 아님              |  410 | `QR_REVOKED`                         |
| 사업장 좌표 없음                              |  409 | `WORKPLACE_LOCATION_REQUIRED`        |
| Key·좌표·정확도·측정 시각 형식 또는 범위 오류 |  400 | `VALIDATION_ERROR`                   |
| 같은 Key가 같은 Fingerprint를 처리 중         |  409 | `CONFLICT`                           |
| 같은 Key를 다른 Fingerprint에 재사용          |  409 | `IDEMPOTENCY_KEY_REUSED`             |
| 위치 정확도 초과 또는 측정 시각 신선도 오류   |  422 | `LOCATION_INVALID`                   |
| 반올림 전 거리 100m 초과                      |  422 | `OUTSIDE_WORKPLACE_RADIUS`           |
| 처리 대상 근무 없음                           |  404 | `ATTENDANCE_WORK_CASE_NOT_FOUND`     |
| 처리 대상 근무 또는 완료 후보 복수            |  409 | `ATTENDANCE_WORK_CASE_AMBIGUOUS`     |
| 이미 출퇴근 완료                              |  409 | `ATTENDANCE_ALREADY_COMPLETED`       |
| 상태·시간창·다른 Key 동시 요청 경합           |  409 | `ATTENDANCE_STATE_CONFLICT`          |
| Deadlock·Lock Timeout이 총 3회 뒤에도 지속    |  503 | `ATTENDANCE_TEMPORARILY_UNAVAILABLE` |

MVP 스캔 지원 환경은 브랜드·최소 Version 대신 실행 Capability로 판정합니다. HTTPS 또는 로컬
Secure Context에서 `navigator.mediaDevices.getUserMedia`, Geolocation API,
`BarcodeDetector`가 존재하고 `BarcodeDetector.getSupportedFormats()`에 `qr_code`가 있을
때만 지원합니다. 하나라도 없거나 카메라·위치 권한이 거부되면 Token 직접 입력 없이 지원
환경 안내를 표시합니다. Decoder 의존성 추가는 별도 승인 대상입니다.

현재 좌표·근태·Settlement·범용 Claim Column과 Index는 이 M5 근태 계약을 충족하므로
M5 근태 전용 Migration은 추가하지 않습니다. 운영 규모의 MySQL `EXPLAIN`에서 성능 문제가
확인될 때만 별도 관리자 승인 Index를 검토합니다. M6 7.0.0 Settlement 계약은 #72가 먼저
현재 지급 동작을 `SCHEDULED` 기반으로 정합화한 뒤 #171 신규 immutable Migration을 적용합니다.

## 문서

| Method | Path                                               | 권한          | 계약                                                     |
| ------ | -------------------------------------------------- | ------------- | -------------------------------------------------------- |
| GET    | `/api/documents`                                   | 문서 접근자   | 문서 Page                                                |
| GET    | `/api/documents/{documentId}`                      | 문서 접근자   | 문서 Item과 허용 `versions[]`                            |
| POST   | `/api/documents`                                   | WORKER        | 보건증 Multipart 업로드                                  |
| PATCH  | `/api/documents/{documentId}`                      | 보건증 소유자 | `{issuedDate}`                                           |
| DELETE | `/api/documents/{documentId}`                      | 보건증 소유자 | `204`                                                    |
| GET    | `/api/documents/{documentId}/file`                 | 문서 접근자   | Query `mode=view|download`, 파일 Stream                  |
| GET    | `/api/documents/{documentId}/shares`               | 보건증 소유자 | 공유 이력                                                |
| POST   | `/api/documents/{documentId}/shares`               | 보건증 소유자 | `{workplaceId}` → `201 {data:{shareId}}`                 |
| DELETE | `/api/documents/{documentId}/shares/{workplaceId}` | 보건증 소유자 | 멱등 `204`                                               |

### 목록과 상세

`GET /api/documents`는 `workplaceId?`, `docType?`, `page?`, `size?`만 Query로 받습니다.
`source`는 Query가 아니라 서버가 현재 사용자 기준으로 계산합니다. OWN 문서는 문서당 한 행,
공유 보건증은 `(documentId,workCaseId)`당 한 행이며 `totalElements`도 이 가시 행 수입니다.
기본 정렬은 `createdAt DESC, documentId DESC, workCaseId DESC NULLS LAST`입니다.

`source=OWN`은 현재 사용자가 문서 소유자일 때이고, `source=SHARED`는 유효한 보건증 공유를
받은 OWNER 또는 근로계약 당사자인 WORKER일 때입니다.

SHARED `HEALTH_CERTIFICATE`의 단건 상세는 목록의 같은 SHARED Item이 반환한
`workCaseId`를 `GET /api/documents/{documentId}?workCaseId={workCaseId}`로 반드시
전달합니다. 서버는 Query가 없을 때 유일한 관계를 자동 선택하거나 복수 관계를 정렬해 첫
행으로 축약하지 않습니다. 전달된 관계는 현재 사용자, 문서, Work Case, 사업장 OWNER,
문서 소유 WORKER, ACTIVE 공유와 현재 유효 시간을 함께 검증합니다.

SHARED 보건증에서 `workCaseId`가 없거나 관계가 틀리거나 비가시면 다른 관계로 fallback하지
않고 `404 RESOURCE_NOT_FOUND`입니다. 문서가 식별된 당사자 불일치는
`PARTY_ACCESS_DENIED`, 철회·만료·근무 종료는 `DOCUMENT_UNAVAILABLE`로
`DOCUMENT_DETAIL_VIEW` DENIED 감사를 정확히 한 번 Commit합니다. 미존재 문서는 DB 감사
대상이 아닙니다. OWN 보건증과 문서 자체 `work_case_id`가 있는 근로계약 당사자는 Query 없는
기존 상세 경로를 유지하고, 파일 Endpoint는 유효 공유 관계가 하나 이상이면 허용합니다.

목록과 `GET /api/documents/{documentId}`는 다음 Item을 함께 사용하고 상세만 `versions[]`를
추가합니다.

```json
{
  "documentId": 5,
  "docType": "HEALTH_CERTIFICATE",
  "status": "ACTIVE",
  "fileName": "보건증_20260601_김알바.jpg",
  "mimeType": "image/jpeg",
  "issuedDate": "2026-06-01",
  "expiresDate": "2027-06-01",
  "latestVersion": 1,
  "source": "SHARED",
  "sharedByName": "김알바",
  "workplaceId": 1,
  "workplaceName": "강남점",
  "workCaseId": 201,
  "capabilities": {
    "canView": true,
    "canDownload": true,
    "canShare": false,
    "canDelete": false
  },
  "createdAt": "2026-06-01T01:00:00Z"
}
```

- DB 상태가 `ACTIVE`인 문서만 후보입니다. `DRAFT`, `AWAITING_SIGNATURE`, `SIGNED`,
  `CANCELED`, `DELETED`는 목록·상세·파일에서 제외합니다.
- 보건증 외부 상태는 서울 날짜 기준 `expiresDate < today`이면 `EXPIRED`, 아니면
  `ACTIVE`입니다. 만료 보건증은 소유자에게만 계속 보이고 공유 대상에서는 숨깁니다.
  근로계약서 외부 상태는 `ACTIVE`입니다.
- 외부 만료 날짜 필드명은 `expiresDate`이며 `expiryDate`·`expiresOn`을 사용하지 않습니다.
- OWN 보건증은 `sharedByName`, `workplaceId`, `workplaceName`, `workCaseId`가 모두 null이고,
  SHARED 보건증은 모두 필수입니다. 근로계약서는 `expiresDate=null`이고 연결 사업장·Work
  Case가 필수이며 OWN의 `sharedByName`은 null, SHARED의 `sharedByName`은 문서 소유자
  이름입니다. `issuedDate`는 두 문서 유형 모두 필수입니다.
- `fileName`은 정제한 이름으로 서버가 조립합니다. 보건증은
  `보건증_{발급일}_{소유자이름}.{ext}`, 근로계약서는
  `근로계약서_{사업장명}_{발급일}_{근로자이름}.pdf`이며 제어문자와 경로 구분자를 제거합니다.
- `versions[]`는 최신순이며 보건증은 ORIGINAL Version 1, 근로계약서는 최신 SIGNED
  Version만 포함합니다. Version Item은 `versionNo`, `versionType`, `mimeType`,
  `sizeBytes`, `createdAt`만 반환합니다.
- `latestVersion`, `mimeType`, `fileName`, Version Item과 파일 Stream은 같은 허용 Version을
  기준으로 합니다. ORIGINAL 계약서, 전자동의 증거, Storage Key, 임시 Key, Checksum,
  내부 사용자 ID는 외부에 노출하지 않습니다.
- 목록에 포함된 행의 `canView`·`canDownload`는 true입니다. `canShare`는 ACTIVE OWN
  보건증에 신규 공유 후보가 하나 이상일 때만 true이고, `canDelete`는 OWN 보건증만
  true입니다. 실제 요청은 권한·관계를 다시 검증합니다.

### 보건증 등록·수정·삭제

`POST /api/documents`는 `docType=HEALTH_CERTIFICATE`, `file`, `issuedDate` Multipart를
받습니다. 재등록은 기존 문서에 Version을 추가하지 않고 새 문서와 ORIGINAL Version 1을
만듭니다. 기존 보건증은 자동 삭제하지 않고 만료 표시 규칙에 따라 문서함에 보존합니다.
사용자 업로드에서 `EMPLOYMENT_CONTRACT`는 받지 않습니다.

- `issuedDate`는 서울 서버 수신 날짜보다 미래일 수 없고, `expiresDate`는 서버가
  `issuedDate.plusYears(1)`로 계산합니다. 윤년은 Java `LocalDate`를 따르며 이미 만료되는
  과거 발급일도 허용합니다. 미래 값은 `400 VALIDATION_ERROR`와
  `fieldErrors.field=issuedDate`이며 클라이언트 만료일은 받지 않습니다.
- 파일은 정확히 10 MiB 이하 JPG·PNG·PDF이며 확장자·선언 MIME·Signature가 모두
  일치해야 합니다. Checksum은 SHA-256 32byte이고 저장 확장자는 검증된 내용에 따라
  소문자 `jpg`, `png`, `pdf` 중 하나로 정합니다.
- 최종 Key는 `health-certificates/{ownerUserId}/{documentId}/v1.{ext}`, 임시 Key는
  `health-certificates/{ownerUserId}/{documentId}/.pending/v1.{ext}`입니다. Key는 응답과
  일반 로그에 노출하지 않습니다.
- `PATCH /api/documents/{documentId}`는 소유 WORKER의 보건증 `issuedDate`만 수정합니다.
  파일 교체·Version 추가는 받지 않으며 만료 상태가 되어도 공유 행의 저장 상태를 바꾸지
  않고 매 요청 유효성으로 즉시 접근을 제거합니다.
- 보건증의 DB `documents.status`는 `ACTIVE|DELETED`만 사용하고 외부 EXPIRED는 날짜로만
  계산합니다.
- `DELETE`는 문서 잠금 뒤 `documents.status=DELETED`와 모든 ACTIVE 공유의
  `REVOKED/revoked_at`을 한 트랜잭션에서 Commit합니다. Version·파일·Checksum·감사는
  보존하고 같은 소유자의 반복 삭제는 204입니다. 근로계약서 DELETE는
  `409 CONTRACT_RETENTION_REQUIRED`, 없는 문서와 비소유 문서는 404입니다.

이미 만료되는 `issuedDate`의 POST·PATCH 성공 응답은 서버가 계산한 `expiresDate`와
`status=EXPIRED`를 포함합니다. 정확한 HTTP Status와 그 밖의 Body 필드는 승인 Patch가
값을 정하지 않았으므로 구현 이슈에서 별도 보호 계약으로 확정하기 전까지 추정하지 않습니다.

### 보건증 공유

공유 생성 요청자는 Work Case ID나 OWNER ID를 보내지 않습니다. 서버는 ACTIVE OWN 보건증,
미만료, ACTIVE 사업장, 정확히 하나의 `ACCEPTED`·`READY` Work Case를 검증해
`work_case_id`와 `shared_with_user_id`를 결정합니다. 복수 후보는 409이며 임의 선택하지
않습니다. 문서가 없거나 삭제·비소유·잘못된 유형이면 404이고, 사업장 없음·비활성·후보 없음·
만료 보건증은 `400 VALIDATION_ERROR`의 `fieldErrors.field=workplaceId`입니다.

- 새 행은 `purpose=HEALTH_CERTIFICATE`, `status=ACTIVE`입니다. 같은 문서·Work Case·OWNER·
  목적의 ACTIVE 공유가 이미 있으면 409입니다. 철회 행은 되살리지 않고 재공유 시 새 행을
  만듭니다.
- 철회는 해당 사업장과 연결된 현재 문서의 모든 ACTIVE 공유를 REVOKED로 바꾸고 같은 서버
  시각을 기록합니다. 대상이 없어도 소유자 요청이면 204입니다.
- 접근 때마다 공유 ACTIVE, 문서 ACTIVE·미만료, 사업장 ACTIVE, Work Case 상태
  `ACCEPTED|READY|IN_PROGRESS`, `now < endsAt`을 함께 검증합니다. 별도 만료 Batch나
  저장 상태 전이는 사용하지 않으며 `document_shares.expires_at`은 접근 유효성이나
  `effectiveUntil` 계산 근거로 사용하지 않습니다.
- 공유 이력 Item은 `shareId`, `workplaceId`, `workplaceName`, `workCaseId`, 계산
  `status`, `sharedAt`, `revokedAt`, `effectiveUntil`만 포함합니다. 상태는 REVOKED 우선,
  저장 ACTIVE지만 관계가 끝났으면 EXPIRED, 모두 유효하면 ACTIVE입니다.
  `effectiveUntil`은 Work Case 종료와 보건증 만료일 다음 서울 자정 중 빠른 시각입니다.
  이력은 최신 생성순입니다.
- 공유 이력 GET도 공통 `{data:{content,page}}` 목록 Envelope를 사용합니다.

### 파일 응답·접근 감사

`mode`는 `view` 또는 `download`이고 생략하면 `view`입니다. 다른 값은
`400 VALIDATION_ERROR`입니다. 허용 MIME은 `image/jpeg`, `image/png`,
`application/pdf`이며 그 밖의 저장 Metadata는
`application/octet-stream`, `attachment`, `X-Content-Type-Options: nosniff`로 보냅니다.
Disposition은 정제한 ASCII `filename`과 RFC 5987 `filename*`을 함께 사용합니다.
`view`는 `inline`, `download`는 `attachment`를 사용합니다.
`Cache-Control: private, no-store`, `Accept-Ranges: none`이며 Range 요청은 지원하지 않습니다.

- 계약 당사자와 보건증 소유자·유효 공유자만 허용 Version을 받습니다. 보이지 않는 문서,
  비당사자, 삭제·철회·만료는 모두 `404 RESOURCE_NOT_FOUND`이며 403·410으로 존재를
  구분하지 않습니다.
- 최종 Object Checksum을 먼저 검증하고 없거나 불일치하면 같은 Version의 결정적 임시
  Object를 검증해 반환·승격을 재시도합니다. 둘 다 복구할 수 없으면 Bytes를 보내지 않고
  `500 INTERNAL_ERROR`입니다.
- 파일 action은 `HEALTH_CERT_FILE_VIEW|HEALTH_CERT_FILE_DOWNLOAD|CONTRACT_FILE_VIEW|`
  `CONTRACT_FILE_DOWNLOAD`, 상세 action은 `DOCUMENT_DETAIL_VIEW`입니다. 목록과 공유 변경은
  감사하지 않습니다.
- 문서 식별 뒤 허용·거부마다 정확히 한 행을 먼저 Commit합니다. ALLOWED는 실제 허용
  Version ID가 필수이고 사유는 null입니다. DENIED는 Version을 식별했으면 그 ID를 기록하고,
  사유는 문서 유형·동작에 허용된 `PARTY_ACCESS_DENIED`, `DOCUMENT_UNAVAILABLE`,
  `SIGNED_VERSION_UNAVAILABLE`, `FILE_UNAVAILABLE`, `CHECKSUM_MISMATCH` 중 하나입니다.
- DENIED가 허용 Version을 식별하기 전이거나 허용 Version 자체가 없으면
  `document_version_id=null`입니다.
- 보건증 파일은 `SIGNED_VERSION_UNAVAILABLE`을 사용하지 않고, 문서 상세는 파일을 읽지
  않으므로 `PARTY_ACCESS_DENIED|DOCUMENT_UNAVAILABLE`만 사용합니다.
- 공유 만료·철회, 보건증 만료·삭제와 근무 관계 종료로 접근이 사라지면 감사 사유는
  `DOCUMENT_UNAVAILABLE`이고 외부 응답은 404입니다.
- 감사 Commit 실패 시 성공 Metadata Body·파일 응답 Header·파일 Bytes를 보내지 않고
  공통 `500 INTERNAL_ERROR` 오류 Envelope를 반환합니다. 미인증이나 없는 문서는 DB 감사
  대신 최소 `traceId` 보안 로그만 남깁니다. 일반 로그에는 사용자 ID, 파일명, Storage Key,
  Checksum, 문서 개인정보를 남기지 않습니다.

문서 API의 외부 오류는 `VALIDATION_ERROR`, `AUTH_REQUIRED`, `FORBIDDEN`, `ROLE_MISMATCH`,
`RESOURCE_NOT_FOUND`, `CONFLICT`, `CONTRACT_RETENTION_REQUIRED`, `INTERNAL_ERROR`로 제한합니다.

### 근로계약서 보존·폐기

근로계약서 보존 기준일은 `work_cases.ends_at`의 서울 날짜이고 만료 시각은 그 날짜에 3년을
더한 서울 자정입니다. 사용자는 계약서를 삭제할 수 없습니다. 매일 02:00 서울 시각 Job이
`documentId ASC` Keyset 100건씩 처리합니다.

각 대상은 짧은 트랜잭션에서 문서를 먼저 DELETED로 Commit해 접근을 차단한 뒤 모든
Version의 최종·결정적 임시 Object를 멱등 삭제합니다. 실패해도 ACTIVE로 복원하지 않고 다음
Job이 만료 DELETED 문서를 다시 선택합니다. `documents`, Version Metadata·Checksum,
서명·공유·접근 감사·계약 관계 행은 기간 제한 없이 보존합니다. 별도 purge 이력 테이블이나
Column은 추가하지 않고 `updated_at`을 완료 표시로 사용하지 않습니다.
완료 Marker가 없더라도 모든 Keyset Page를 끝까지 순회해 앞의 100건이 뒤 대상의 처리를
굶기지 않도록 합니다.

시스템 생성 계약서에 `work_case_id`가 없으면 데이터 손상으로 격리하고 운영
`INTERNAL_ERROR` 경보를 남깁니다. Object 미존재는 삭제 성공입니다. 운영 로그에는 traceId,
documentId, versionId, 단계와 성공·실패 Enum만 남기고 Storage Key·Checksum·당사자 정보는
남기지 않습니다.

## 알림

### 알림 유형과 수신자

MVP 알림은 아래 6종뿐이며 다른 유형을 만들지 않습니다. 알림은 이미 확정된 도메인 이벤트를
알리기만 하고 어떤 도메인 상태나 자금 흐름도 바꾸지 않습니다. 수신자가 둘인 유형은 수신자마다
별도 알림 행을 만들며, 수신자가 아닌 사용자는 그 알림을 조회하거나 읽음 처리할 수 없습니다.

| `notiType`            | 발생 시점                      | 수신자          | `sourceType`     | `sourceId`가 가리키는 행 |
| --------------------- | ------------------------------ | --------------- | ---------------- | ------------------------ |
| `WORK_CASE_CONFIRMED` | 초대 수락으로 근무가 확정될 때 | OWNER, WORKER   | `WORK_CASE`      | `work_cases`             |
| `ESCROW_HELD`         | 임금 예치가 성립할 때          | OWNER, WORKER   | `ESCROW`         | `escrows`                |
| `SETTLED`             | 정산 지급이 완료될 때          | OWNER, WORKER   | `SETTLEMENT`     | `settlements`            |
| `REFUNDED`            | 노쇼 환불이 완료될 때          | OWNER, WORKER   | `SETTLEMENT`     | `settlements`            |
| `DOC_SHARED`          | 보건증이 근무에 공유될 때      | OWNER           | `DOCUMENT_SHARE` | `document_shares`        |
| `WAGE_REPORTED`       | 임금분쟁이 생성될 때           | 신고 상대방 1인 | `DISPUTE`        | `disputes`               |

**이벤트 식별자와 이동 대상은 서로 다른 값입니다.** `sourceType`+`sourceId`는 이 알림을 만든
도메인 행을, `workCaseId`는 눌렀을 때 이동할 근무를 가리킵니다. 둘을 한 값으로 합치면 같은
근무에서 두 번째로 일어난 정상 이벤트(보건증 재공유, 종료된 분쟁 뒤의 새 분쟁)가 중복으로
오인돼 영구히 누락됩니다. 6종 모두 근무 문맥에서 발생하므로 `workCaseId`는 항상 존재합니다.
`SETTLED`와 `REFUNDED`는 같은 정산 행을 가리키지만 `notiType`이 달라 중복 Key가 갈립니다.

동일 이벤트 중복은 `(수신자, notiType, sourceType, sourceId)` 유일성으로 막습니다. 같은 Key가
다시 들어오면 새 알림을 만들지 않고 기존 알림을 유지합니다. `title`과 `content`는 서버가
완성된 문구로 만들어 저장하고 응답에 그대로 실으며 클라이언트는 유형별 문구를 조립하지
않습니다.

알림 적재는 원인 도메인 Transaction이 실패하면 확정되지 않습니다. 그 역방향은 성립하지 않아
알림 적재가 실패해도 이미 Commit된 예치·정산·환불·근무 확정은 유지되고 도메인 응답은
성공입니다. 알림 Endpoint 전체가 동작하지 않아도 자금 처리는 계속 동작해야 합니다.

### `GET /api/notifications`

- 인증 사용자 본인의 알림만 최신순(`createdAt` 내림차순, 동률은 `notificationId` 내림차순)으로
  반환합니다. 수신자를 Query·Path·Body로 받지 않습니다.
- Query는 `page?`, `size?`만 받고 공통 페이지네이션 기본값을 따릅니다.
- 응답은 공통 `{data:{content,page}}` 목록 Envelope입니다.

| 필드             | 설명                                            |
| ---------------- | ----------------------------------------------- |
| `notificationId` | 양의 정수                                       |
| `notiType`       | 위 6종 중 하나                                  |
| `title`          | 서버가 만든 완성 문구                           |
| `content`        | 서버가 만든 완성 문구                           |
| `sourceType`     | 위 표의 이벤트 유형                             |
| `sourceId`       | 이벤트를 만든 도메인 행 식별자                  |
| `workCaseId`     | 이동 대상 근무 식별자                           |
| `isRead`         | boolean                                         |
| `readAt`         | 읽은 시각. 읽지 않았으면 `null`                 |
| `createdAt`      | 생성 시각                                       |

### `GET /api/notifications/unread-count`

`{"data":{"unreadCount":3}}`로 본인의 안읽음 개수만 반환합니다. 목록 Envelope에 개수를 덧붙이지
않아 공통 목록 계약을 유지합니다. 개수는 저장된 읽음 상태에서 계산하며 별도 집계 값을 두지
않고 0 미만이 될 수 없습니다.

### `PATCH /api/notifications/{notificationId}/read`

본인 알림 한 건을 읽음으로 바꾸고 `readAt`을 그때의 시각으로 확정합니다. 성공은
`204 No Content`입니다. 이미 읽은 알림에 다시 요청해도 성공이며 `readAt`은 최초 값을
유지합니다. **전체 읽음 처리는 이 계약에 두지 않습니다.**

### `GET /api/notifications/stream`

인증 사용자 본인의 알림 스트림을 여는 Server-Sent Events Operation입니다. 수신자를
Query·Path·Body로 받지 않습니다.

- **신호만 전달하고 알림 본문을 싣지 않습니다.** 새 알림이 적재된 수신자에게 "다시 조회하라"는
  신호를 보내며, 알림의 내용은 위 목록·개수 Operation이 계속 단독으로 소유합니다. 같은 알림이
  두 가지 형태로 존재하지 않습니다.
- 신호는 적재가 확정된 뒤에만 보냅니다. 적재에 실패한 수신자에게는 보내지 않습니다.
- 응답은 중간 프록시의 Buffering을 끄는 지시를 포함하고, 조용한 구간에도 배포 프록시의 읽기
  타임아웃보다 짧은 주기로 연결 유지 프레임을 보냅니다. 연결 타임아웃은 애플리케이션이
  명시하며 컨테이너 기본값에 맡기지 않습니다.
- 연결 종료·타임아웃·오류 어느 경로로 끊기든 서버는 그 연결을 즉시 버립니다.

**이 Operation은 알림 기능의 전제가 아닙니다.** 스트림 연결이 실패하거나 끊긴 동안에도 목록
조회와 읽음 처리는 정상 동작하며, 신호 전달 실패는 알림 적재 결과와 도메인 응답을 바꾸지
않습니다. 놓친 신호의 관찰 가능한 영향은 "화면을 다시 열어야 보인다"까지이고 알림 자체는
저장돼 있어 유실되지 않습니다.

구독 연결은 애플리케이션 인스턴스 메모리에 유지하므로 인스턴스가 둘 이상이면 다른 인스턴스에
연결된 사용자는 신호를 받지 못합니다. 이 한계는 위 독립성으로 흡수하며 공유 Broker 도입은
별도 제품 결정과 새 명세 Patch로 승인합니다.

### 알림 오류

| 상황                                | HTTP | Code                 |
| ----------------------------------- | ---: | -------------------- |
| 인증 없음                           |  401 | `AUTH_REQUIRED`      |
| 타인의 알림 또는 존재하지 않는 알림 |  404 | `RESOURCE_NOT_FOUND` |
| `page`·`size` 허용 범위 밖          |  400 | `VALIDATION_ERROR`   |

타인 알림은 존재를 드러내지 않도록 403이 아니라 404로 응답합니다.

## 외부 결제

외부 결제 Endpoint는 `DEC-OPEN-PAYMENT-PROVIDER`가 승인된 뒤 이 문서에 추가합니다. 결정
전에는 경로 또는 Payload를 규범 계약으로 추정하지 않습니다.
