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
- 중복 삽입 거부는 오류가 아닙니다. 서버는 이를 "이미 알림이 있다"로 해석하며, 이 거부가
  원인 도메인 트랜잭션에 영향을 주지 않는다는 경계는 `SPEC-384-01`이 정합니다.
- `noti_type`은 `SPEC-382-01`이 확정한 6종만, `source_type`은 같은 Patch의 이벤트 표에 있는
  다섯 값(`WORK_CASE`, `ESCROW`, `SETTLEMENT`, `DOCUMENT_SHARE`, `DISPUTE`)만 저장할 수
  있습니다. 두 제약 모두 Database가 강제합니다.
- `noti_type`과 `source_type`의 짝은 `SPEC-382-01`의 표대로만 저장할 수 있습니다. 예를 들어
  `DOC_SHARED`에 `DISPUTE`를 붙인 행은 Database가 거부합니다.
- 읽음 상태와 읽음 시각은 항상 정합합니다 — 읽은 알림은 `read_at`이 반드시 있고, 읽지 않은
  알림은 반드시 없습니다. 한쪽만 갱신하는 경로는 Database가 거부합니다.
- `recipient_user_id`와 `work_case_id`는 실재하는 행만 가리키며, 알림이 남아 있는 사용자·근무
  행은 삭제되지 않습니다(`ON DELETE RESTRICT`).
- 조회는 수신자별 최신순과 수신자별 안읽음 개수 두 가지뿐이며, 두 접근 경로 모두 인덱스로
  지원합니다.

### 이벤트 식별자와 이동 대상의 참조 무결성

`SPEC-382-01`이 정한 대로 **이벤트 식별자(`source_type`+`source_id`)와 이동
대상(`work_case_id`)은 서로 다른 컬럼**입니다. 둘을 한 값으로 합치면 같은 근무의 두 번째
정상 이벤트(보건증 재공유, 종료된 분쟁 뒤의 새 분쟁)가 유일 제약에 막혀 영구히 누락됩니다.

`source_id`는 유형마다 다른 Table을 가리키는 다형 참조라 단일 Foreign Key를 걸 수 없습니다.
대신 **이동 대상인 `work_case_id`에 `work_cases` FK를 걸어** 존재하지 않는 근무를 가리키는
행이 들어오지 못하게 합니다. `source_id`는 `source_type`과 짝지어진 Table의 행을 가리켜야
하며 이 규칙은 쓰기 경로가 지킵니다. Database가 강제하지 못하는 유일한 항목입니다.

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
    work_case_id       bigint unsigned NOT NULL,
    title              varchar(100)    NOT NULL,
    content            varchar(500)    NOT NULL,
    is_read            tinyint(1)      NOT NULL DEFAULT 0,
    read_at            datetime(6)     DEFAULT NULL,
    created_at         datetime(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_notifications_event (recipient_user_id, noti_type, source_type, source_id),
    KEY idx_notifications_recipient_created (recipient_user_id, created_at, id),
    KEY idx_notifications_recipient_unread (recipient_user_id, is_read),
    KEY fk_notifications_work_case (work_case_id),
    CONSTRAINT fk_notifications_recipient FOREIGN KEY (recipient_user_id)
        REFERENCES users (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT fk_notifications_work_case FOREIGN KEY (work_case_id)
        REFERENCES work_cases (id) ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_notifications_noti_type CHECK (
        noti_type IN ('WORK_CASE_CONFIRMED', 'ESCROW_HELD', 'SETTLED',
                      'REFUNDED', 'DOC_SHARED', 'WAGE_REPORTED')
    ),
    -- noti_type 과 source_type 의 짝을 SPEC-382-01 의 이벤트 표대로만 허용한다.
    CONSTRAINT ck_notifications_source_type CHECK (
        (noti_type = 'WORK_CASE_CONFIRMED' AND source_type = 'WORK_CASE')
        OR (noti_type = 'ESCROW_HELD' AND source_type = 'ESCROW')
        OR (noti_type IN ('SETTLED', 'REFUNDED') AND source_type = 'SETTLEMENT')
        OR (noti_type = 'DOC_SHARED' AND source_type = 'DOCUMENT_SHARE')
        OR (noti_type = 'WAGE_REPORTED' AND source_type = 'DISPUTE')
    ),
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
- [ ] 같은 근무의 두 번째 보건증 공유와 두 번째 분쟁이 서로 다른 `source_id`를 가져 유일
      제약에 막히지 않는다.
- [ ] `SETTLED`와 `REFUNDED`가 같은 `source_id`를 가져도 서로를 막지 않는다.
- [ ] `SPEC-382-01`의 6종 밖 `noti_type` 삽입이 거부된다.
- [ ] 이벤트 표와 다른 `noti_type`·`source_type` 짝 삽입이 거부된다.
- [ ] 존재하지 않는 `work_case_id` 삽입이 Foreign Key로 거부된다.
- [ ] `is_read`와 `read_at` 중 한쪽만 채운 삽입·갱신이 거부된다.
- [ ] 알림이 있는 사용자 행과 근무 행 삭제가 거부된다.
- [ ] 수신자별 최신순 조회와 안읽음 개수 조회가 각각 인덱스를 사용한다.
- [ ] 폐기 가능한 로컬 Database를 초기화하고 전체 Migration을 재적용해 통과한다.
- [ ] Schema Snapshot이 실제 Database와 일치한다.
