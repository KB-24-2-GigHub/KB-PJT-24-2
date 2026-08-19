-- 영상 체크포인트 03: A/B는 수 시간 근무한 IN_PROGRESS 상태, C는 NO_SHOW 상태입니다.
-- A/B는 종료 시각이 1분 지났지만 퇴근 누락 유예 2시간 안이어서 QR로 정상 퇴근할 수 있습니다.

SOURCE /seed/demo-reset.inc

SET @demo_wage = 100000;
SET @owner_available = 600000;
SET @owner_locked = @demo_wage * 3;
SET @owner_bank_balance = 5000000;
SET @worker_a_available = 0;
SET @worker_b_available = 0;
SET @worker_c_available = 0;

SOURCE /seed/demo-users.inc
SOURCE /seed/demo-primary-workplace.inc

SET @a_start = DATE_SUB(@seed_now, INTERVAL 4 HOUR);
SET @a_end = DATE_SUB(@seed_now, INTERVAL 1 MINUTE);
SET @a_status = 'IN_PROGRESS';

SET @b_start = DATE_SUB(@seed_now, INTERVAL 4 HOUR);
SET @b_end = DATE_SUB(@seed_now, INTERVAL 1 MINUTE);
SET @b_status = 'IN_PROGRESS';

SET @c_start = DATE_SUB(@seed_now, INTERVAL 2 HOUR);
SET @c_end = DATE_ADD(@seed_now, INTERVAL 2 HOUR);
SET @c_status = 'NO_SHOW';

SOURCE /seed/demo-three-accepted-work-cases.inc
SOURCE /seed/demo-three-hold-ledger.inc

SET @qr_token_id = (
    SELECT id FROM qr_tokens
    WHERE workplace_id = @workplace_id AND status = 'ACTIVE'
);

INSERT INTO attendance_records (
    work_case_id, worker_id, qr_token_id, attendance_type,
    captured_at, attempted_at, distance_meters, accuracy_meters,
    result, failure_reason, created_at
) VALUES
    (
        @work_case_a_id, @worker_a_id, @qr_token_id, 'CHECK_IN',
        DATE_SUB(@a_start, INTERVAL 10 MINUTE), DATE_SUB(@a_start, INTERVAL 10 MINUTE),
        8.20, 5.00, 'SUCCESS', NULL, DATE_SUB(@a_start, INTERVAL 10 MINUTE)
    ),
    (
        @work_case_b_id, @worker_b_id, @qr_token_id, 'CHECK_IN',
        DATE_ADD(@b_start, INTERVAL 30 MINUTE), DATE_ADD(@b_start, INTERVAL 30 MINUTE),
        12.40, 7.00, 'SUCCESS', NULL, DATE_ADD(@b_start, INTERVAL 30 MINUTE)
    );

COMMIT;

SELECT
    'video-03-check-out' AS scenario_key,
    @seed_now AS seed_now,
    @workplace_id AS workplace_id,
    @work_case_a_id AS normal_work_case_id,
    DATE_SUB(@a_start, INTERVAL 10 MINUTE) AS normal_checked_in_at,
    @work_case_b_id AS late_work_case_id,
    DATE_ADD(@b_start, INTERVAL 30 MINUTE) AS late_checked_in_at,
    30 AS late_minutes,
    @work_case_c_id AS no_show_work_case_id,
    'NO_SHOW' AS no_show_status,
    '지각 차감 실제 지급은 #424 구현 후 검증' AS late_settlement_note,
    'Demo1234!' AS demo_password;
