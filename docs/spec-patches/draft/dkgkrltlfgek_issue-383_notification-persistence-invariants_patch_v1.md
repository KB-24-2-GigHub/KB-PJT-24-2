---
patch_id: SPEC-383-01
status: draft
issue: 383
base_spec_version: 8.0.0
targets:
  - requirement: ALERT-002
  - decision: DEC-OPEN-NOTIFICATION-CONTRACT
---

# SPEC-383-01: 알림 저장 불변식 — 중복 방지와 읽음 상태 정합성

이 Patch는 `SPEC-382-01`이 정한 알림 계약이 **저장 계층에서 지켜야 하는 불변식**만 정합니다.
Migration, DDL 또는 통합 Schema 산출물을 변경할 권한은 부여하지 않습니다. 아래 '제안 DDL'은
관리자 승인 범위를 정하기 위한 참고이며 적용된 저장소 변경이 아닙니다.

## 추가 사항

### 저장 불변식

- **동일 이벤트 중복은 애플리케이션 검사가 아니라 Database 유일 제약이 막습니다.** 키는
  `(recipient_user_id, noti_type, source_type, source_id)`이며, 같은 키의 두 번째 삽입은
  경합 상황에서도 Database가 거부합니다.
- 중복 삽입 거부는 오류가 아닙니다. 서버는 이를 "이미 알림이 있다"로 해석해 원인 도메인
  트랜잭션을 실패시키지 않습니다.
- `noti_type`은 `SPEC-382-01`이 확정한 6종만 저장할 수 있고, `source_type`은 `WORK_CASE`만
  저장할 수 있습니다. 두 제약 모두 Database가 강제합니다.
- 읽음 상태와 읽음 시각은 항상 정합합니다 — 읽은 알림은 `read_at`이 반드시 있고, 읽지 않은
  알림은 반드시 없습니다. 한쪽만 갱신하는 경로는 Database가 거부합니다.
- `recipient_user_id`는 실재하는 사용자만 가리키며, 알림이 남아 있는 사용자 행은 삭제되지
  않습니다(`ON DELETE RESTRICT`).
- 조회는 수신자별 최신순과 수신자별 안읽음 개수 두 가지뿐이며, 두 접근 경로 모두 인덱스로
  지원합니다.

### 다형 참조

`source_id`에는 Foreign Key를 두지 않습니다. `source_type`이 가리키는 대상이 유형 확장에
따라 달라질 수 있고, 지금 `work_cases`로 FK를 걸면 유형을 늘릴 때 제약을 떨어뜨리는
Migration이 먼저 필요해집니다. 대신 `source_type` CHECK로 현재 허용 대상을 좁게 유지하고,
근무 삭제 자체가 다른 계약에서 이미 `RESTRICT`로 막혀 있어 고아 참조가 생기지 않습니다.

### 제안 DDL (관리자 승인 대상, 이 Patch로 적용되지 않음)

마지막 적용 Migration은 `V202608121403`입니다. 신규 Migration 1건
(`V202608161000__create_notifications.sql`, 버전은 적용 시점에 맞춰 관리자가 확정합니다)을
제안하며 기존 Migration은 수정하지 않습니다.

```sql
CREATE TABLE notifications (
    id                 bigint unsigned NOT NULL AUTO_INCREMENT,
    recipient_user_id  bigint unsigned NOT NULL,
    noti_type          varchar(30)     NOT NULL,
    source_type        varchar(20)     NOT NULL,
    source_id          bigint unsigned NOT NULL,
    title              varchar(100)    NOT NULL,
    content            varchar(500)    NOT NULL,
    is_read            tinyint(1)      NOT NULL DEFAULT 0,
    read_at            datetime(6)     DEFAULT NULL,
    created_at         datetime(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_notifications_event (recipient_user_id, noti_type, source_type, source_id),
    KEY idx_notifications_recipient_created (recipient_user_id, created_at, id),
    KEY idx_notifications_recipient_unread (recipient_user_id, is_read),
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_user_id)
        REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_notifications_noti_type CHECK (
        noti_type IN ('WORK_CASE_CONFIRMED', 'ESCROW_HELD', 'SETTLED',
                      'REFUNDED', 'DOC_SHARED', 'WAGE_REPORTED')
    ),
    CONSTRAINT ck_notifications_source_type CHECK (source_type IN ('WORK_CASE')),
    CONSTRAINT ck_notifications_read_at CHECK (
        (is_read = 1 AND read_at IS NOT NULL) OR (is_read = 0 AND read_at IS NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
```

승인 시 함께 갱신해야 하는 파생 산출물은 `docs/database/` Schema Snapshot,
`docs/agent/SCHEMA_OVERVIEW.md`, `docs/agent/MODULE_BOUNDARIES.json`의 `notifications` 소유
모듈(`notification`)입니다.

## 완료 조건

- [ ] 같은 `(수신자, notiType, sourceType, sourceId)`로 두 번 삽입하면 두 번째가 Database
      유일 제약으로 거부된다.
- [ ] 동시 삽입 경합에서도 알림이 한 건만 남는다.
- [ ] 중복 거부가 원인 도메인 트랜잭션을 실패시키지 않는다.
- [ ] `SPEC-382-01`의 6종 밖 `noti_type`과 `WORK_CASE` 밖 `source_type` 삽입이 거부된다.
- [ ] `is_read`와 `read_at` 중 한쪽만 채운 삽입·갱신이 거부된다.
- [ ] 알림이 있는 사용자 행 삭제가 거부된다.
- [ ] 수신자별 최신순 조회와 안읽음 개수 조회가 각각 인덱스를 사용한다.
- [ ] 폐기 가능한 로컬 Database를 초기화하고 전체 Migration을 재적용해 통과한다.
- [ ] Schema Snapshot이 실제 Database와 일치한다.
