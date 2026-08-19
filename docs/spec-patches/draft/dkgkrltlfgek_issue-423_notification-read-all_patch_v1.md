---
patch_id: SPEC-423-01
status: draft
issue: 423
base_spec_version: 8.1.0
targets:
  - operation: GET /api/notifications
  - operation: PATCH /api/notifications/read-all
  - requirement: SPEC-382-01
---

# SPEC-423-01: 알림 전체 읽음 처리와 안읽음 목록 조회

## 추가 사항

정식 SPEC `8.1.0`은 `PATCH /api/notifications/{notificationId}/read` 절에서 **"전체 읽음 처리는 이 계약에 두지 않습니다."**라고 정하고, `GET /api/notifications`의 Query를 `page?`, `size?`로 한정한다. 이 Patch는 두 문장을 모두 바꾼다.

읽음 상태만 바꾼다. 알림 행을 삭제하지 않고 `notifications` 테이블도 바꾸지 않는다.

### `PATCH /api/notifications/read-all` (신규)

- 인증 사용자 본인의 안읽음 알림 전부를 한 번에 읽음으로 바꾼다. 수신자를 Query·Path·Body로 받지 않는다. 기존 단건 읽음과 같은 원칙이다.
- 성공은 `204 No Content`이며 응답 Body가 없다. 처리한 건수를 돌려주지 않는다. 개수가 필요하면 `GET /api/notifications/unread-count`가 이미 소유한다.
- 안읽음이 0건이어도 성공이다. 누를 것이 없는 상태를 오류로 만들면 화면이 그 분기를 따로 다뤄야 한다.
- 이미 읽은 알림의 `readAt`은 바뀌지 않는다. 최초 읽은 시각이 보존된다는 단건 읽음의 성질을 전체 읽음도 그대로 지킨다.
- 다른 사용자의 알림은 어떤 경우에도 바뀌지 않는다.

### `GET /api/notifications` — `unreadOnly` Query 추가

- `unreadOnly?` boolean Query를 추가한다. `true`면 안읽음 알림만 반환하고, 목록 Envelope의 `page.totalElements`도 안읽음 기준으로 센다.
- **기본값은 `false`다.** 기본값을 `true`로 두면 Query를 붙이지 않는 기존 호출자의 응답이 조용히 달라진다. 목록에서 읽은 알림을 감추는 것은 호출자의 선택으로 남긴다.
- `page?`, `size?`와 정렬(`createdAt` 내림차순, 동률은 `notificationId` 내림차순)은 바뀌지 않는다.
- 응답 항목의 필드 구성은 바뀌지 않는다. `unreadOnly=true`일 때 `isRead`는 항상 `false`, `readAt`은 항상 `null`이다.

### 화면

- 알림 모달은 목록을 `unreadOnly=true`로 조회한다. 따라서 항목을 눌러 읽음 처리하면 그 항목은 목록에서 사라지고, 모달을 닫았다 다시 열어도 돌아오지 않는다.
- 모달 헤더 우측에 전체 읽음 수단을 둔다. 안읽음이 없으면 노출하지 않는다.
- 전체 읽음 뒤 목록은 비고 안읽음 배지는 `0`이 된다.
- 알림 요청이 실패해도 상단 바와 나머지 화면은 계속 동작한다. 기존 실패 격리를 유지한다.

## 완료 조건

- [ ] `PATCH /api/notifications/read-all`이 본인 안읽음 알림을 전부 읽음으로 바꾸고 `204`를 반환한다.
- [ ] 안읽음이 0건일 때 호출해도 `204`로 성공한다.
- [ ] 전체 읽음이 다른 사용자의 알림을 바꾸지 않는다.
- [ ] 전체 읽음이 이미 읽은 알림의 `readAt`을 덮어쓰지 않는다.
- [ ] 미인증 요청은 전체 읽음을 수행하지 못한다.
- [ ] `GET /api/notifications?unreadOnly=true`가 안읽음 알림만 반환하고 `page.totalElements`도 안읽음 기준이다.
- [ ] `unreadOnly`를 붙이지 않은 조회의 결과가 이 Patch 이전과 같다.
- [ ] 알림 모달에서 항목을 누르면 목록에서 사라지고, 모달을 다시 열어도 나타나지 않는다.
- [ ] 모달 헤더의 전체 읽음 수단으로 배지가 `0`이 되고 목록이 빈 상태를 보여준다.
- [ ] 안읽음이 없으면 전체 읽음 수단이 보이지 않는다.
