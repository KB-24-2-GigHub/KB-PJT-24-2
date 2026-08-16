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

MVP 알림은 아래 6종이며 다른 유형을 만들지 않습니다. 모든 유형은 근무 문맥에서 발생하므로
대상 식별자는 `sourceType = WORK_CASE`와 해당 `workCaseId`입니다.

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

### 적재 규칙

- 알림은 원인이 되는 도메인 트랜잭션과 같은 트랜잭션에서 확정합니다. 도메인 트랜잭션이
  실패하면 알림도 남지 않습니다.
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
- `sourceType`: `WORK_CASE`
- `sourceId`: 대상 `workCaseId`
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
- [ ] 원인 도메인 트랜잭션이 실패하면 알림이 남지 않는다.
- [ ] `GET /api/notifications`가 본인 알림만 최신순으로, 공통 `{content,page}` Envelope로 반환한다.
- [ ] 타인 알림 읽음 요청이 `404 RESOURCE_NOT_FOUND`이고 응답이 알림의 존재를 드러내지 않는다.
- [ ] 읽음 처리 후 `isRead`가 참이고 `readAt`이 채워지며, 재요청해도 `readAt`이 바뀌지 않는다.
- [ ] `GET /api/notifications/unread-count`가 본인의 안읽음 개수만 반환한다.
- [ ] 알림 목록에 근무 확정과 정산 완료 두 건이 시간순으로 표시된다(`MVP_SCOPE.md` 4-1).
