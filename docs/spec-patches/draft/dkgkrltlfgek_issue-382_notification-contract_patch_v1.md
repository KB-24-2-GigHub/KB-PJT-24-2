---
patch_id: SPEC-382-01
status: draft
issue: 382
base_spec_version: 8.0.0
targets:
  - requirement: ALERT-001
  - requirement: ALERT-002
  - decision: DEC-OPEN-NOTIFICATION-CONTRACT
  - operation: GET /api/notifications
  - operation: GET /api/notifications/unread-count
  - operation: PATCH /api/notifications/{notificationId}/read
---

# SPEC-382-01: 인앱 알림 계약 — 유형·적재·목록·읽음 처리

## 추가 사항

### 알림 유형과 수신자

MVP 알림은 아래 6종이며 다른 유형을 만들지 않습니다. 알림은 이미 확정된 도메인 이벤트를
사용자에게 알리기만 하고, 어떤 도메인 상태나 자금 흐름도 바꾸지 않습니다.

| `notiType`            | 발생 시점                      | 수신자          |
| --------------------- | ------------------------------ | --------------- |
| `WORK_CASE_CONFIRMED` | 초대 수락으로 근무가 확정될 때 | OWNER, WORKER   |
| `ESCROW_HELD`         | 임금 예치가 성립할 때          | OWNER, WORKER   |
| `SETTLED`             | 정산 지급이 완료될 때          | OWNER, WORKER   |
| `REFUNDED`            | 노쇼 환불이 완료될 때          | OWNER, WORKER   |
| `DOC_SHARED`          | 보건증이 근무에 공유될 때      | OWNER           |
| `WAGE_REPORTED`       | 임금분쟁이 생성될 때           | 신고 상대방 1인 |

수신자가 둘인 유형은 수신자마다 별도 알림 행을 만듭니다. 수신자가 아닌 사용자는 해당
알림을 조회하거나 읽음 처리할 수 없습니다.

### 이벤트 식별자와 이동 대상은 서로 다른 값이다

알림은 두 가지 다른 것을 가리킵니다. **무엇이 이 알림을 만들었는지**(중복 판정 기준)와
**눌렀을 때 어디로 가는지**(이동 대상)입니다. 둘을 한 값으로 합치면 같은 근무에서 두 번째로
일어난 정상 이벤트가 중복으로 오인돼 영구히 누락됩니다. 예를 들어 보건증을 다시 등록해
재공유하면 새 공유 행이 생기고, 종료된 분쟁 뒤에 같은 근무에서 새 분쟁이 열릴 수 있습니다.
둘 다 서로 다른 이벤트이므로 각각 알림이 나가야 합니다.

- `sourceType` + `sourceId`는 **이벤트를 만든 실제 도메인 행**을 가리킵니다.
- `workCaseId`는 **이동 대상**이며 6종 모두 근무 문맥에서 발생하므로 항상 존재합니다.

| `notiType`            | `sourceType`     | `sourceId`가 가리키는 행 |
| --------------------- | ---------------- | ------------------------ |
| `WORK_CASE_CONFIRMED` | `WORK_CASE`      | `work_cases`             |
| `ESCROW_HELD`         | `ESCROW`         | `escrows`                |
| `SETTLED`             | `SETTLEMENT`     | `settlements`            |
| `REFUNDED`            | `SETTLEMENT`     | `settlements`            |
| `DOC_SHARED`          | `DOCUMENT_SHARE` | `document_shares`        |
| `WAGE_REPORTED`       | `DISPUTE`        | `disputes`               |

`SETTLED`와 `REFUNDED`는 같은 정산 행을 가리키지만 `notiType`이 달라 중복 키가 갈립니다.

### 적재 규칙

- 알림 적재는 원인 도메인 트랜잭션의 성공을 되돌리지 않습니다. 두 경계의 관계는
  `SPEC-384-01`이 단독으로 정하며 이 Patch는 중복 정의하지 않습니다.
- 동일 이벤트 중복은 `(수신자, notiType, sourceType, sourceId)` 유일성으로 막습니다. 같은
  키가 다시 들어오면 새 알림을 만들지 않고 기존 알림을 유지합니다.
- `title`과 `content`는 서버가 완성된 문구로 만들어 저장하고 응답에 그대로 싣습니다.
  클라이언트는 유형별 문구를 조립하지 않습니다.

### 목록 조회

`GET /api/notifications`는 인증 사용자 본인의 알림만 최신순(`createdAt` 내림차순, 동률은
`notificationId` 내림차순)으로 반환합니다. Query는 `page?`, `size?`만 받고 공통
페이지네이션 기본값을 따릅니다. 응답은 공통 `{data:{content,page}}` 목록 Envelope입니다.

`content` 원소 필드는 다음과 같습니다.

- `notificationId`: 양의 정수
- `notiType`: 위 6종 중 하나
- `title`, `content`: 서버가 만든 문구
- `sourceType`: 위 표의 이벤트 유형
- `sourceId`: 이벤트를 만든 도메인 행 식별자
- `workCaseId`: 이동 대상 근무 식별자
- `isRead`: boolean
- `readAt`: 읽은 시각. 읽지 않았으면 `null`
- `createdAt`: 생성 시각

### 안읽음 개수

`GET /api/notifications/unread-count`는 `{data:{unreadCount}}`로 본인의 안읽음 개수만
반환합니다. 목록 Envelope에 개수를 덧붙이지 않아 공통 목록 계약을 유지합니다.

### 읽음 처리

`PATCH /api/notifications/{notificationId}/read`는 본인 알림 한 건을 읽음으로 바꾸고
`readAt`을 그때의 시각으로 확정합니다. 전체 읽음 처리는 이 계약에 두지 않습니다.

- 이미 읽은 알림에 다시 요청해도 성공이며 `readAt`은 최초 값을 유지합니다.
- 응답은 `204 No Content`입니다.

### 오류

| 상황                                | 상태 | Code                 |
| ----------------------------------- | ---- | -------------------- |
| 미인증                              | 401  | `AUTH_REQUIRED`      |
| 타인의 알림 또는 존재하지 않는 알림 | 404  | `RESOURCE_NOT_FOUND` |
| `page`·`size` 허용 범위 밖          | 400  | `VALIDATION_ERROR`   |

타인 알림은 존재를 드러내지 않도록 403이 아니라 404로 응답합니다.

### 전달 방식

이 계약의 전달 방식은 목록 조회입니다. 실시간 Push는 포함하지 않으며, 도입할 경우 목록
조회 계약을 바꾸지 않는 별도 Patch로 정의합니다.

## 완료 조건

- [ ] 위 6종 외의 `notiType`이 저장되거나 응답에 나타나지 않는다.
- [ ] 수신자가 둘인 유형에서 OWNER와 WORKER가 각각 자신의 알림 한 건씩을 받는다.
- [ ] 같은 `(수신자, notiType, sourceType, sourceId)` 이벤트가 두 번 발생해도 알림은 한 건이다.
- [ ] 같은 근무에서 보건증을 다시 공유하면 두 번째 `DOC_SHARED` 알림이 정상으로 생성된다.
- [ ] 종료된 분쟁 뒤 같은 근무에 새 분쟁이 열리면 두 번째 `WAGE_REPORTED` 알림이 생성된다.
- [ ] `SETTLED`와 `REFUNDED`가 같은 정산 행을 가리켜도 서로를 중복으로 막지 않는다.
- [ ] 알림 조회·읽음 처리가 근무, 정산, 예치 상태를 바꾸지 않는다.
- [ ] `GET /api/notifications`가 본인 알림만 최신순으로, 공통 `{content,page}` Envelope로 반환한다.
- [ ] 타인 알림 읽음 요청이 `404 RESOURCE_NOT_FOUND`이고 응답이 알림의 존재를 드러내지 않는다.
- [ ] 읽음 처리 후 `isRead`가 참이고 `readAt`이 채워지며, 재요청해도 `readAt`이 바뀌지 않는다.
- [ ] `GET /api/notifications/unread-count`가 본인의 안읽음 개수만 반환한다.
- [ ] 알림 목록에 근무 확정과 정산 완료 두 건이 시간순으로 표시된다(`MVP_SCOPE.md` 4-1).
