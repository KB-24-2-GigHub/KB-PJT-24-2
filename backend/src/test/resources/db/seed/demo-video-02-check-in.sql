-- 영상 체크포인트 02: A는 10분 전 출근, B는 30분 지각 출근, C는 다음 Scheduler에서
-- NO_SHOW로 전환될 수 있도록 실행 시각을 기준으로 만듭니다.

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

SET @a_start = DATE_ADD(@seed_now, INTERVAL 10 MINUTE);
SET @a_end = DATE_ADD(@a_start, INTERVAL 4 HOUR);
SET @a_status = 'READY';

SET @b_start = DATE_SUB(@seed_now, INTERVAL 30 MINUTE);
SET @b_end = DATE_ADD(@b_start, INTERVAL 4 HOUR);
SET @b_status = 'READY';

-- 시작 +1시간 경계를 이미 넘겨 다음 60초 Scheduler 주기 안에 NO_SHOW가 됩니다.
SET @c_start = DATE_SUB(@seed_now, INTERVAL 60 MINUTE);
SET @c_end = DATE_ADD(@c_start, INTERVAL 4 HOUR);
SET @c_status = 'READY';

SOURCE /seed/demo-three-accepted-work-cases.inc
SOURCE /seed/demo-three-hold-ledger.inc

COMMIT;

SELECT
    'video-02-check-in' AS scenario_key,
    @seed_now AS seed_now,
    @workplace_id AS workplace_id,
    @work_case_a_id AS normal_work_case_id,
    @a_start AS normal_starts_at,
    @work_case_b_id AS late_work_case_id,
    @b_start AS late_starts_at,
    DATE_ADD(@b_start, INTERVAL 1 HOUR) AS late_no_show_at,
    @work_case_c_id AS no_show_work_case_id,
    DATE_ADD(@c_start, INTERVAL 1 HOUR) AS no_show_at,
    'hardworker / 김성실' AS worker_a,
    'ilovesleep / 이수면' AS worker_b,
    'submarine / 박잠수' AS worker_c,
    'Demo1234!' AS demo_password,
    100 AS radius_meters;
