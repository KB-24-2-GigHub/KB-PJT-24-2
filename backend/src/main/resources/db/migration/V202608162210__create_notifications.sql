-- #383 인앱 알림 저장소를 만든다. 계약은 SPEC-382-01, 저장 불변식은 SPEC-383-01이다.
-- 이벤트 식별자(source_type, source_id)와 이동 대상(work_case_id)은 서로 다른 값이다.
-- 둘을 한 값으로 합치면 같은 근무의 보건증 재공유나 후속 분쟁이 중복으로 오인돼 누락된다.
-- 동일 이벤트 중복은 애플리케이션 선검사가 아니라 아래 UNIQUE가 막는다.
CREATE TABLE notifications (
    id                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    recipient_user_id BIGINT UNSIGNED NOT NULL,
    noti_type         VARCHAR(30)     NOT NULL,
    source_type       VARCHAR(20)     NOT NULL,
    source_id         BIGINT UNSIGNED NOT NULL,
    work_case_id      BIGINT UNSIGNED NOT NULL,
    title             VARCHAR(100)    NOT NULL,
    content           VARCHAR(500)    NOT NULL,
    is_read           TINYINT UNSIGNED NOT NULL DEFAULT 0,
    read_at           DATETIME(6)     NULL,
    created_at        DATETIME(6)     NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_notifications_event (recipient_user_id, noti_type, source_type, source_id),
    KEY idx_notifications_recipient_created (recipient_user_id, created_at, id),
    KEY idx_notifications_recipient_unread (recipient_user_id, is_read),
    KEY fk_notifications_work_case (work_case_id),
    CONSTRAINT fk_notifications_recipient
        FOREIGN KEY (recipient_user_id) REFERENCES users (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    -- source_id는 유형마다 다른 Table을 가리키는 다형 참조라 단일 FK를 걸 수 없다.
    -- 이동 대상인 work_case_id에만 FK를 걸어 존재하지 않는 근무를 가리키지 못하게 한다.
    CONSTRAINT fk_notifications_work_case
        FOREIGN KEY (work_case_id) REFERENCES work_cases (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_notifications_noti_type CHECK (
        noti_type IN (
            'WORK_CASE_CONFIRMED', 'ESCROW_HELD', 'SETTLED',
            'REFUNDED', 'DOC_SHARED', 'WAGE_REPORTED'
        )
    ),
    -- noti_type과 source_type의 짝을 SPEC-382-01의 이벤트 표대로만 허용한다.
    -- SETTLED와 REFUNDED는 같은 정산 행을 가리키지만 noti_type이 달라 중복 키가 갈린다.
    CONSTRAINT ck_notifications_source_type CHECK (
        (noti_type = 'WORK_CASE_CONFIRMED' AND source_type = 'WORK_CASE')
        OR (noti_type = 'ESCROW_HELD' AND source_type = 'ESCROW')
        OR (noti_type IN ('SETTLED', 'REFUNDED') AND source_type = 'SETTLEMENT')
        OR (noti_type = 'DOC_SHARED' AND source_type = 'DOCUMENT_SHARE')
        OR (noti_type = 'WAGE_REPORTED' AND source_type = 'DISPUTE')
    ),
    CONSTRAINT ck_notifications_read_at CHECK (
        (is_read = 1 AND read_at IS NOT NULL)
        OR (is_read = 0 AND read_at IS NULL)
    )
) ENGINE = InnoDB
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
