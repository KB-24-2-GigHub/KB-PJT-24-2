-- 영상 체크포인트 04: 냠냠과자점 3개 지점, 누적 근무, 신뢰 배지, 3년 보존 만료를 만듭니다.
-- 배지는 저장값만 꾸미지 않고 실제 Work/Attendance/Settlement 원천으로 재계산됩니다.

SOURCE /seed/demo-reset.inc

SET @history_wage = 100000;
SET @history_work_count = 65;
SET @history_funding_amount = @history_wage * @history_work_count;
SET @owner_available = @history_wage * 3;
SET @owner_locked = 0;
SET @owner_bank_balance = 10000000;
SET @worker_a_available = @history_wage * 30;
SET @worker_b_available = @history_wage * 20;
SET @worker_c_available = @history_wage * 12;

SOURCE /seed/demo-users.inc
SOURCE /seed/demo-primary-workplace.inc

SET @workplace_1_id = @workplace_id;

INSERT INTO workplaces (
    owner_user_id, business_registration_number, name,
    representative_name, road_address, detail_address, phone,
    latitude, longitude, radius_meters, status, created_at, updated_at
) VALUES
    (
        @owner_id, '0000001002', '냠냠과자점 2호점',
        '긱사장', '서울 성동구 연무장길 50', NULL, '0200001002',
        37.5421000, 127.0561000, 100.00, 'ACTIVE',
        DATE_SUB(@seed_now, INTERVAL 2 YEAR), @seed_now
    ),
    (
        @owner_id, '0000001003', '냠냠과자점 3호점',
        '긱사장', '서울 광진구 아차산로 200', NULL, '0200001003',
        37.5407000, 127.0701000, 100.00, 'ACTIVE',
        DATE_SUB(@seed_now, INTERVAL 1 YEAR), @seed_now
    );

SET @workplace_2_id = (
    SELECT id FROM workplaces WHERE business_registration_number = '0000001002'
);
SET @workplace_3_id = (
    SELECT id FROM workplaces WHERE business_registration_number = '0000001003'
);

INSERT INTO qr_tokens (
    workplace_id, issued_by_user_id, token_nonce, status, created_at
) VALUES
    (@workplace_2_id, @owner_id, RANDOM_BYTES(16), 'ACTIVE', @seed_now),
    (@workplace_3_id, @owner_id, RANDOM_BYTES(16), 'ACTIVE', @seed_now);

-- 김성실: 아래 29건과 3년 초과 1건이 모두 정시 출근 완료되어 30/30, Lv.3입니다.
INSERT INTO work_cases (
    employer_id, worker_id, workplace_id, title,
    starts_at, ends_at, break_minutes, break_paid,
    workplace_name, workplace_address,
    workplace_latitude, workplace_longitude, allowed_radius_meters,
    agreed_wage, terms_version, status, created_at, updated_at
)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 29
)
SELECT
    @owner_id,
    @worker_a_id,
    CASE MOD(n, 3)
        WHEN 1 THEN @workplace_1_id
        WHEN 2 THEN @workplace_2_id
        ELSE @workplace_3_id
    END,
    CONCAT('[3YEAR-A-', LPAD(n, 2, '0'), '] 김성실 근무'),
    TIMESTAMP(DATE_SUB(@seed_today, INTERVAL (n * 30) DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@seed_today, INTERVAL (n * 30) DAY), '17:00:00'),
    60, 0,
    CASE MOD(n, 3)
        WHEN 1 THEN '냠냠과자점 1호점'
        WHEN 2 THEN '냠냠과자점 2호점'
        ELSE '냠냠과자점 3호점'
    END,
    CASE MOD(n, 3)
        WHEN 1 THEN '서울 광진구 능동로 195-16'
        WHEN 2 THEN '서울 성동구 연무장길 50'
        ELSE '서울 광진구 아차산로 200'
    END,
    CASE MOD(n, 3) WHEN 1 THEN 37.5481384 WHEN 2 THEN 37.5421000 ELSE 37.5407000 END,
    CASE MOD(n, 3) WHEN 1 THEN 127.0733972 WHEN 2 THEN 127.0561000 ELSE 127.0701000 END,
    100.00, @history_wage, 1, 'COMPLETED',
    TIMESTAMP(DATE_SUB(@seed_today, INTERVAL (n * 30 + 1) DAY), '08:00:00'),
    TIMESTAMP(DATE_SUB(@seed_today, INTERVAL (n * 30) DAY), '17:30:00')
FROM seq;

INSERT INTO attendance_records (
    work_case_id, worker_id, qr_token_id, attendance_type,
    captured_at, attempted_at, distance_meters, accuracy_meters,
    result, failure_reason, created_at
)
SELECT
    id, @worker_a_id, NULL, 'CHECK_IN',
    DATE_SUB(starts_at, INTERVAL 10 MINUTE), DATE_SUB(starts_at, INTERVAL 10 MINUTE),
    8.00, 5.00, 'SUCCESS', NULL, DATE_SUB(starts_at, INTERVAL 10 MINUTE)
FROM work_cases
WHERE worker_id = @worker_a_id AND title LIKE '[3YEAR-A-%';

-- 이수면: 20건 중 18건 정시, 2건 30분 지각으로 정확히 90%, Lv.2입니다.
INSERT INTO work_cases (
    employer_id, worker_id, workplace_id, title,
    starts_at, ends_at, break_minutes, break_paid,
    workplace_name, workplace_address,
    workplace_latitude, workplace_longitude, allowed_radius_meters,
    agreed_wage, terms_version, status, created_at, updated_at
)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 20
)
SELECT
    @owner_id, @worker_b_id,
    CASE MOD(n, 3) WHEN 1 THEN @workplace_1_id WHEN 2 THEN @workplace_2_id ELSE @workplace_3_id END,
    CONCAT('[3YEAR-B-', LPAD(n, 2, '0'), '] 이수면 근무'),
    TIMESTAMP(DATE_SUB(@seed_today, INTERVAL (n * 24 + 3) DAY), '10:00:00'),
    TIMESTAMP(DATE_SUB(@seed_today, INTERVAL (n * 24 + 3) DAY), '18:00:00'),
    60, 0,
    CASE MOD(n, 3) WHEN 1 THEN '냠냠과자점 1호점' WHEN 2 THEN '냠냠과자점 2호점' ELSE '냠냠과자점 3호점' END,
    CASE MOD(n, 3) WHEN 1 THEN '서울 광진구 능동로 195-16' WHEN 2 THEN '서울 성동구 연무장길 50' ELSE '서울 광진구 아차산로 200' END,
    CASE MOD(n, 3) WHEN 1 THEN 37.5481384 WHEN 2 THEN 37.5421000 ELSE 37.5407000 END,
    CASE MOD(n, 3) WHEN 1 THEN 127.0733972 WHEN 2 THEN 127.0561000 ELSE 127.0701000 END,
    100.00, @history_wage, 1, 'COMPLETED',
    TIMESTAMP(DATE_SUB(@seed_today, INTERVAL (n * 24 + 4) DAY), '09:00:00'),
    TIMESTAMP(DATE_SUB(@seed_today, INTERVAL (n * 24 + 3) DAY), '18:30:00')
FROM seq;

INSERT INTO attendance_records (
    work_case_id, worker_id, qr_token_id, attendance_type,
    captured_at, attempted_at, distance_meters, accuracy_meters,
    result, failure_reason, created_at
)
SELECT
    id, @worker_b_id, NULL, 'CHECK_IN',
    CASE WHEN CAST(SUBSTRING(title, 10, 2) AS UNSIGNED) <= 18
         THEN starts_at ELSE DATE_ADD(starts_at, INTERVAL 30 MINUTE) END,
    CASE WHEN CAST(SUBSTRING(title, 10, 2) AS UNSIGNED) <= 18
         THEN starts_at ELSE DATE_ADD(starts_at, INTERVAL 30 MINUTE) END,
    11.00, 6.00, 'SUCCESS', NULL,
    CASE WHEN CAST(SUBSTRING(title, 10, 2) AS UNSIGNED) <= 18
         THEN starts_at ELSE DATE_ADD(starts_at, INTERVAL 30 MINUTE) END
FROM work_cases
WHERE worker_id = @worker_b_id AND title LIKE '[3YEAR-B-%';

-- 박잠수: 15건 중 12건 정상 완료, 3건 NO_SHOW로 80%, Lv.1입니다.
INSERT INTO work_cases (
    employer_id, worker_id, workplace_id, title,
    starts_at, ends_at, break_minutes, break_paid,
    workplace_name, workplace_address,
    workplace_latitude, workplace_longitude, allowed_radius_meters,
    agreed_wage, terms_version, status, created_at, updated_at
)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 15
)
SELECT
    @owner_id, @worker_c_id,
    CASE MOD(n, 3) WHEN 1 THEN @workplace_1_id WHEN 2 THEN @workplace_2_id ELSE @workplace_3_id END,
    CONCAT('[3YEAR-C-', LPAD(n, 2, '0'), '] 박잠수 근무'),
    TIMESTAMP(DATE_SUB(@seed_today, INTERVAL (n * 20 + 6) DAY), '08:00:00'),
    TIMESTAMP(DATE_SUB(@seed_today, INTERVAL (n * 20 + 6) DAY), '16:00:00'),
    60, 0,
    CASE MOD(n, 3) WHEN 1 THEN '냠냠과자점 1호점' WHEN 2 THEN '냠냠과자점 2호점' ELSE '냠냠과자점 3호점' END,
    CASE MOD(n, 3) WHEN 1 THEN '서울 광진구 능동로 195-16' WHEN 2 THEN '서울 성동구 연무장길 50' ELSE '서울 광진구 아차산로 200' END,
    CASE MOD(n, 3) WHEN 1 THEN 37.5481384 WHEN 2 THEN 37.5421000 ELSE 37.5407000 END,
    CASE MOD(n, 3) WHEN 1 THEN 127.0733972 WHEN 2 THEN 127.0561000 ELSE 127.0701000 END,
    100.00, @history_wage, 1,
    CASE WHEN n <= 12 THEN 'COMPLETED' ELSE 'NO_SHOW' END,
    TIMESTAMP(DATE_SUB(@seed_today, INTERVAL (n * 20 + 7) DAY), '07:00:00'),
    TIMESTAMP(DATE_SUB(@seed_today, INTERVAL (n * 20 + 6) DAY), '16:30:00')
FROM seq;

INSERT INTO attendance_records (
    work_case_id, worker_id, qr_token_id, attendance_type,
    captured_at, attempted_at, distance_meters, accuracy_meters,
    result, failure_reason, created_at
)
SELECT
    id, @worker_c_id, NULL, 'CHECK_IN', starts_at, starts_at,
    9.00, 5.00, 'SUCCESS', NULL, starts_at
FROM work_cases
WHERE worker_id = @worker_c_id
  AND title LIKE '[3YEAR-C-%'
  AND status = 'COMPLETED';

-- 3년을 넘긴 계약 1건은 Metadata만 남고 문서 상태는 DELETED입니다.
SET @expired_start = TIMESTAMP(DATE_SUB(DATE_SUB(@seed_today, INTERVAL 3 YEAR), INTERVAL 2 DAY), '09:00:00');
SET @expired_end = TIMESTAMP(DATE_SUB(DATE_SUB(@seed_today, INTERVAL 3 YEAR), INTERVAL 2 DAY), '17:00:00');

INSERT INTO work_cases (
    employer_id, worker_id, workplace_id, title,
    starts_at, ends_at, break_minutes, break_paid,
    workplace_name, workplace_address,
    workplace_latitude, workplace_longitude, allowed_radius_meters,
    agreed_wage, terms_version, status, created_at, updated_at
) VALUES (
    @owner_id, @worker_a_id, @workplace_1_id, '[3YEAR-EXPIRED] 파기된 계약 근무',
    @expired_start, @expired_end, 60, 0,
    '냠냠과자점 1호점', '서울 광진구 능동로 195-16',
    37.5481384, 127.0733972, 100.00,
    @history_wage, 1, 'COMPLETED',
    DATE_SUB(@expired_start, INTERVAL 1 DAY), @expired_end
);
SET @expired_work_case_id = LAST_INSERT_ID();

INSERT INTO attendance_records (
    work_case_id, worker_id, qr_token_id, attendance_type,
    captured_at, attempted_at, distance_meters, accuracy_meters,
    result, failure_reason, created_at
) VALUES (
    @expired_work_case_id, @worker_a_id, NULL, 'CHECK_IN',
    DATE_SUB(@expired_start, INTERVAL 10 MINUTE), DATE_SUB(@expired_start, INTERVAL 10 MINUTE),
    7.00, 5.00, 'SUCCESS', NULL, DATE_SUB(@expired_start, INTERVAL 10 MINUTE)
);

INSERT INTO work_contracts (
    work_case_id, employer_id, worker_id, title,
    starts_at, ends_at, break_minutes, break_paid,
    workplace_name, workplace_address,
    workplace_latitude, workplace_longitude, allowed_radius_meters,
    agreed_wage, source_terms_version, terms_snapshot, accepted_at, created_at
) VALUES (
    @expired_work_case_id, @owner_id, @worker_a_id, '보존기간 만료 근로계약서',
    @expired_start, @expired_end, 60, 0,
    '냠냠과자점 1호점', '서울 광진구 능동로 195-16',
    37.5481384, 127.0733972, 100.00,
    @history_wage, 1, JSON_OBJECT('demo', TRUE, 'retentionExpired', TRUE),
    DATE_SUB(@expired_start, INTERVAL 1 DAY), DATE_SUB(@expired_start, INTERVAL 1 DAY)
);

INSERT INTO documents (
    created_by_user_id, owner_user_id, work_case_id,
    document_type, status, issued_on, created_at, updated_at
) VALUES (
    @owner_id, @owner_id, @expired_work_case_id,
    'EMPLOYMENT_CONTRACT', 'DELETED', DATE(@expired_start),
    DATE_SUB(@expired_start, INTERVAL 1 DAY), @seed_now
);
SET @expired_document_id = LAST_INSERT_ID();

INSERT INTO document_versions (
    document_id, version_no, version_type, storage_key,
    mime_type, size_bytes, checksum, created_at
) VALUES
    (
        @expired_document_id, 1, 'ORIGINAL',
        CONCAT('contracts/', @expired_work_case_id, '/', @expired_document_id, '/v1.pdf'),
        'application/pdf', 1024, UNHEX(SHA2('DEMO-EXPIRED-V1', 256)),
        DATE_SUB(@expired_start, INTERVAL 1 DAY)
    ),
    (
        @expired_document_id, 2, 'SIGNED',
        CONCAT('contracts/', @expired_work_case_id, '/', @expired_document_id, '/v2.pdf'),
        'application/pdf', 1024, UNHEX(SHA2('DEMO-EXPIRED-V2', 256)),
        DATE_SUB(@expired_start, INTERVAL 1 DAY)
    );

-- 완료 근무의 정산·예치 원천이 OWNER Lv.3을 만들고, NO_SHOW 3건은 전액 환불 이력입니다.
INSERT INTO escrows (
    work_case_id, amount, status, held_at, released_at, refunded_at, created_at, updated_at
)
SELECT
    id, agreed_wage,
    CASE WHEN status = 'NO_SHOW' THEN 'REFUNDED' ELSE 'RELEASED' END,
    DATE_SUB(starts_at, INTERVAL 1 HOUR),
    CASE WHEN status = 'COMPLETED' THEN DATE_ADD(ends_at, INTERVAL 1 HOUR) ELSE NULL END,
    CASE WHEN status = 'NO_SHOW' THEN DATE_ADD(starts_at, INTERVAL 2 HOUR) ELSE NULL END,
    DATE_SUB(starts_at, INTERVAL 1 DAY),
    CASE WHEN status = 'NO_SHOW' THEN DATE_ADD(starts_at, INTERVAL 2 HOUR)
         ELSE DATE_ADD(ends_at, INTERVAL 1 HOUR) END
FROM work_cases
WHERE title LIKE '[3YEAR-%';

INSERT INTO settlements (
    work_case_id, amount, worker_paid_amount, owner_refund_amount,
    calculation_reason, calculation_version, calculated_at,
    status, approved_by_user_id,
    due_at, processing_at, completed_at, created_at, updated_at
)
SELECT
    id, agreed_wage,
    CASE WHEN status = 'COMPLETED' THEN agreed_wage ELSE 0 END,
    CASE WHEN status = 'NO_SHOW' THEN agreed_wage ELSE 0 END,
    'LEGACY', 'LEGACY',
    CASE WHEN status = 'NO_SHOW' THEN DATE_ADD(starts_at, INTERVAL 2 HOUR)
         ELSE DATE_ADD(ends_at, INTERVAL 1 HOUR) END,
    CASE WHEN status = 'NO_SHOW' THEN 'REFUNDED' ELSE 'COMPLETED' END,
    @owner_id,
    CASE WHEN status = 'COMPLETED' THEN DATE_ADD(ends_at, INTERVAL 24 HOUR) ELSE NULL END,
    CASE WHEN status = 'NO_SHOW' THEN DATE_ADD(starts_at, INTERVAL 2 HOUR)
         ELSE DATE_ADD(ends_at, INTERVAL 1 HOUR) END,
    CASE WHEN status = 'NO_SHOW' THEN DATE_ADD(starts_at, INTERVAL 2 HOUR)
         ELSE DATE_ADD(ends_at, INTERVAL 1 HOUR) END,
    DATE_SUB(starts_at, INTERVAL 1 DAY),
    CASE WHEN status = 'NO_SHOW' THEN DATE_ADD(starts_at, INTERVAL 2 HOUR)
         ELSE DATE_ADD(ends_at, INTERVAL 1 HOUR) END
FROM work_cases
WHERE title LIKE '[3YEAR-%';

-- 과거 65건의 예치·지급·환불을 지갑 Snapshot과 같은 원장으로 남깁니다. 각 근무는
-- 시간순으로 예치 후 종료되어 OWNER 잠금액이 다시 0이 된 다음 근무로 이어집니다.
INSERT INTO wallet_transactions (
    wallet_id, work_case_id, transaction_type, amount,
    available_before, available_after, locked_before, locked_after,
    reference_type, reference_id, idempotency_key, created_at
)
SELECT
    @owner_wallet_id, NULL, 'FUNDING', @history_funding_amount,
    0, @history_funding_amount, 0, 0,
    'DEMO_SEED', @owner_id, '3YEAR-OWNER-FUNDING',
    DATE_SUB((SELECT MIN(starts_at) FROM work_cases WHERE title LIKE '[3YEAR-%'), INTERVAL 2 HOUR);

INSERT INTO wallet_transactions (
    wallet_id, work_case_id, transaction_type, amount,
    available_before, available_after, locked_before, locked_after,
    reference_type, reference_id, idempotency_key, created_at
)
WITH ordered_cases AS (
    SELECT
        wc.id AS work_case_id,
        wc.starts_at,
        wc.agreed_wage,
        e.id AS escrow_id,
        COALESCE(
            SUM(CASE WHEN wc.status = 'COMPLETED' THEN 1 ELSE 0 END) OVER (
                ORDER BY wc.starts_at, wc.id
                ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
            ),
            0
        ) AS prior_completed_count
    FROM work_cases wc
    JOIN escrows e ON e.work_case_id = wc.id
    WHERE wc.title LIKE '[3YEAR-%'
)
SELECT
    @owner_wallet_id, work_case_id, 'ESCROW_HOLD', agreed_wage,
    @history_funding_amount - prior_completed_count * @history_wage,
    @history_funding_amount - prior_completed_count * @history_wage - agreed_wage,
    0, agreed_wage,
    'ESCROW', escrow_id, CONCAT('3YEAR-HOLD-', work_case_id),
    DATE_SUB(starts_at, INTERVAL 1 HOUR)
FROM ordered_cases;

INSERT INTO wallet_transactions (
    wallet_id, work_case_id, transaction_type, amount,
    available_before, available_after, locked_before, locked_after,
    reference_type, reference_id, idempotency_key, created_at
)
WITH ordered_cases AS (
    SELECT
        wc.id AS work_case_id,
        wc.starts_at,
        wc.ends_at,
        wc.status,
        wc.agreed_wage,
        e.id AS escrow_id,
        COALESCE(
            SUM(CASE WHEN wc.status = 'COMPLETED' THEN 1 ELSE 0 END) OVER (
                ORDER BY wc.starts_at, wc.id
                ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING
            ),
            0
        ) AS prior_completed_count
    FROM work_cases wc
    JOIN escrows e ON e.work_case_id = wc.id
    WHERE wc.title LIKE '[3YEAR-%'
)
SELECT
    @owner_wallet_id,
    work_case_id,
    CASE WHEN status = 'COMPLETED' THEN 'ESCROW_RELEASE' ELSE 'ESCROW_REFUND' END,
    agreed_wage,
    @history_funding_amount - prior_completed_count * @history_wage - agreed_wage,
    CASE WHEN status = 'COMPLETED'
         THEN @history_funding_amount - prior_completed_count * @history_wage - agreed_wage
         ELSE @history_funding_amount - prior_completed_count * @history_wage END,
    agreed_wage,
    0,
    'ESCROW',
    escrow_id,
    CONCAT(
        CASE WHEN status = 'COMPLETED' THEN '3YEAR-OWNER-RELEASE-' ELSE '3YEAR-REFUND-' END,
        work_case_id
    ),
    CASE WHEN status = 'COMPLETED' THEN DATE_ADD(ends_at, INTERVAL 1 HOUR)
         ELSE DATE_ADD(starts_at, INTERVAL 2 HOUR) END
FROM ordered_cases;

INSERT INTO wallet_transactions (
    wallet_id, work_case_id, transaction_type, amount,
    available_before, available_after, locked_before, locked_after,
    reference_type, reference_id, idempotency_key, created_at
)
WITH completed_cases AS (
    SELECT
        wc.id AS work_case_id,
        wc.worker_id,
        wc.ends_at,
        wc.agreed_wage,
        e.id AS escrow_id,
        ROW_NUMBER() OVER (
            PARTITION BY wc.worker_id ORDER BY wc.starts_at, wc.id
        ) - 1 AS prior_worker_completed_count
    FROM work_cases wc
    JOIN escrows e ON e.work_case_id = wc.id
    WHERE wc.title LIKE '[3YEAR-%'
      AND wc.status = 'COMPLETED'
)
SELECT
    CASE worker_id
        WHEN @worker_a_id THEN @worker_a_wallet_id
        WHEN @worker_b_id THEN @worker_b_wallet_id
        ELSE @worker_c_wallet_id
    END,
    work_case_id,
    'ESCROW_RELEASE',
    agreed_wage,
    prior_worker_completed_count * @history_wage,
    (prior_worker_completed_count + 1) * @history_wage,
    0,
    0,
    'ESCROW',
    escrow_id,
    CONCAT('3YEAR-WORKER-RELEASE-', work_case_id),
    DATE_ADD(ends_at, INTERVAL 1 HOUR)
FROM completed_cases;

-- 3호점 오늘 캘린더를 채우는 수락 전 일정 8건입니다. 현재 시각과 무관하게 오늘 날짜를 씁니다.
INSERT INTO work_cases (
    employer_id, worker_id, workplace_id, title,
    starts_at, ends_at, break_minutes, break_paid,
    workplace_name, workplace_address,
    workplace_latitude, workplace_longitude, allowed_radius_meters,
    agreed_wage, terms_version, status, created_at, updated_at
)
WITH RECURSIVE seq(n) AS (
    SELECT 1
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 8
)
SELECT
    @owner_id, NULL, @workplace_3_id,
    CONCAT('[오늘 3호점 일정 ', LPAD(n, 2, '0'), '] 디저트 팝업 운영'),
    TIMESTAMP(@seed_today, MAKETIME(8 + n, 0, 0)),
    TIMESTAMP(@seed_today, MAKETIME(9 + n, 0, 0)),
    0, 0,
    '냠냠과자점 3호점', '서울 광진구 아차산로 200',
    37.5407000, 127.0701000, 100.00,
    @history_wage, 1, 'DRAFT', @seed_now, @seed_now
FROM seq;

COMMIT;

SELECT
    'video-04-three-years' AS scenario_key,
    @seed_now AS seed_now,
    (SELECT COUNT(*) FROM workplaces WHERE owner_user_id = @owner_id) AS workplace_count,
    (SELECT COUNT(*) FROM work_cases WHERE employer_id = @owner_id) AS work_case_count,
    (SELECT COUNT(*) FROM work_cases WHERE workplace_id = @workplace_3_id AND DATE(starts_at) = @seed_today) AS today_store_3_count,
    (SELECT COUNT(*) FROM settlements s JOIN work_cases wc ON wc.id = s.work_case_id
     WHERE wc.employer_id = @owner_id AND s.status = 'COMPLETED') AS owner_completed_count,
    (SELECT COUNT(*) FROM wallet_transactions) AS wallet_transaction_count,
    (SELECT COUNT(*) FROM user_badges) AS badge_rows_before_api_recalculation,
    (SELECT COUNT(*) FROM documents WHERE status = 'DELETED') AS deleted_contract_count,
    'Demo1234!' AS demo_password;
