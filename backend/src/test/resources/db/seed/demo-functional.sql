-- 기능 통합 점검용 SEED입니다.
-- SQL은 전체 초기화와 근태/노쇼/스케줄 원천을 만들고, prepare-demo-seed.js가 실제 API로
-- 초대·수락·계약서·알림·보건증 업로드/공유를 이어서 완성합니다.

SOURCE /seed/demo-reset.inc

SET @demo_wage = 100000;
SET @owner_available = 1200000;
SET @owner_locked = @demo_wage * 2;
SET @owner_bank_balance = 5000000;
SET @worker_a_available = 0;
SET @worker_b_available = 0;
SET @worker_c_available = 0;

SOURCE /seed/demo-users.inc
SOURCE /seed/demo-primary-workplace.inc

SET @accepted_at = DATE_SUB(@seed_now, INTERVAL 1 DAY);
SET @b_start = DATE_SUB(@seed_now, INTERVAL 30 MINUTE);
SET @b_end = DATE_ADD(@b_start, INTERVAL 4 HOUR);
SET @c_start = DATE_SUB(@seed_now, INTERVAL 60 MINUTE);
SET @c_end = DATE_ADD(@c_start, INTERVAL 4 HOUR);

INSERT INTO work_cases (
    employer_id, worker_id, workplace_id, title,
    starts_at, ends_at, break_minutes, break_paid,
    workplace_name, workplace_address,
    workplace_latitude, workplace_longitude, allowed_radius_meters,
    agreed_wage, terms_version, status, created_at, updated_at
) VALUES
    (
        @owner_id, @worker_b_id, @workplace_id, '[FUNCTION] 이수면 지각 출석체크',
        @b_start, @b_end, 30, 0,
        '냠냠과자점 1호점', '서울 광진구 능동로 195-16',
        37.5481384, 127.0733972, 100.00,
        @demo_wage, 1, 'READY', @accepted_at, @seed_now
    ),
    (
        @owner_id, @worker_c_id, @workplace_id, '[FUNCTION] 박잠수 자동 노쇼',
        @c_start, @c_end, 30, 0,
        '냠냠과자점 1호점', '서울 광진구 능동로 195-16',
        37.5481384, 127.0733972, 100.00,
        @demo_wage, 1, 'READY', @accepted_at, @seed_now
    );

SET @functional_b_id = (
    SELECT id FROM work_cases WHERE title = '[FUNCTION] 이수면 지각 출석체크'
);
SET @functional_c_id = (
    SELECT id FROM work_cases WHERE title = '[FUNCTION] 박잠수 자동 노쇼'
);

INSERT INTO work_invitations (
    work_case_id, token_hash, status, expected_terms_version,
    expires_at, accepted_by_user_id, accepted_terms_version, accepted_at, created_at
) VALUES
    (
        @functional_b_id, UNHEX(SHA2('FUNCTION-ACCEPTED-B', 256)), 'ACCEPTED', 1,
        @b_start, @worker_b_id, 1, @accepted_at, @accepted_at
    ),
    (
        @functional_c_id, UNHEX(SHA2('FUNCTION-ACCEPTED-C', 256)), 'ACCEPTED', 1,
        @c_start, @worker_c_id, 1, @accepted_at, @accepted_at
    );

INSERT INTO work_contracts (
    work_case_id, employer_id, worker_id, title,
    starts_at, ends_at, break_minutes, break_paid,
    workplace_name, workplace_address,
    workplace_latitude, workplace_longitude, allowed_radius_meters,
    agreed_wage, source_terms_version, terms_snapshot, accepted_at, created_at
) VALUES
    (
        @functional_b_id, @owner_id, @worker_b_id, '이수면 기능 점검 근로계약서',
        @b_start, @b_end, 30, 0,
        '냠냠과자점 1호점', '서울 광진구 능동로 195-16',
        37.5481384, 127.0733972, 100.00,
        @demo_wage, 1, JSON_OBJECT('demo', TRUE, 'plannedLateMinutes', 30),
        @accepted_at, @accepted_at
    ),
    (
        @functional_c_id, @owner_id, @worker_c_id, '박잠수 기능 점검 근로계약서',
        @c_start, @c_end, 30, 0,
        '냠냠과자점 1호점', '서울 광진구 능동로 195-16',
        37.5481384, 127.0733972, 100.00,
        @demo_wage, 1, JSON_OBJECT('demo', TRUE, 'plannedOutcome', 'NO_SHOW'),
        @accepted_at, @accepted_at
    );

-- 계약이 있으면 EMPLOYMENT_CONTRACT 문서도 함께 있어야 근무 상세를 열 수 있습니다. 계약만
-- 있고 문서가 없는 조합은 서버가 계약서 생성이 끊긴 손상 상태로 보고 예외를 던져 상세 조회가
-- 500이 됩니다(WorkCaseServiceImpl.requireContractIntegrity).
--
-- SQL은 실제 계약서 PDF를 문서 저장소에 만들 수 없으므로 파기(DELETED)로 넣습니다. 파기된
-- 문서는 documentId가 감춰져(WorkCaseServiceImpl.visibleDocumentId) 열리지 않는 '계약서 보기'
-- 링크를 노출하지 않고 문서함 목록에서도 빠집니다. 실제 PDF까지 있는 계약서 확인은 아래
-- prepare-demo-seed.js가 초대 수락 API로 만드는 김성실 근무가 담당합니다.
INSERT INTO documents (
    created_by_user_id, owner_user_id, work_case_id,
    document_type, status, issued_on, created_at, updated_at
) VALUES (
    @owner_id, @owner_id, @functional_b_id,
    'EMPLOYMENT_CONTRACT', 'DELETED', DATE(@b_start), @accepted_at, @accepted_at
);
SET @functional_b_document_id = LAST_INSERT_ID();

INSERT INTO documents (
    created_by_user_id, owner_user_id, work_case_id,
    document_type, status, issued_on, created_at, updated_at
) VALUES (
    @owner_id, @owner_id, @functional_c_id,
    'EMPLOYMENT_CONTRACT', 'DELETED', DATE(@c_start), @accepted_at, @accepted_at
);
SET @functional_c_document_id = LAST_INSERT_ID();

-- 수락 한 번에 ORIGINAL(v1)과 서명본(v2)을 함께 만드는 실제 흐름과 같은 Version 구성입니다
-- (PdfContractArtifactPort). 파기는 Version 행을 지우지 않으므로 그대로 남겨 둡니다
-- (DocumentDeleteServiceImpl).
INSERT INTO document_versions (
    document_id, version_no, version_type, storage_key,
    mime_type, size_bytes, checksum, created_at
) VALUES
    (
        @functional_b_document_id, 1, 'ORIGINAL',
        CONCAT('contracts/', @functional_b_id, '/', @functional_b_document_id, '/v1.pdf'),
        'application/pdf', 1024, UNHEX(SHA2('FUNCTION-CONTRACT-B-V1', 256)), @accepted_at
    ),
    (
        @functional_b_document_id, 2, 'SIGNED',
        CONCAT('contracts/', @functional_b_id, '/', @functional_b_document_id, '/v2.pdf'),
        'application/pdf', 1024, UNHEX(SHA2('FUNCTION-CONTRACT-B-V2', 256)), @accepted_at
    ),
    (
        @functional_c_document_id, 1, 'ORIGINAL',
        CONCAT('contracts/', @functional_c_id, '/', @functional_c_document_id, '/v1.pdf'),
        'application/pdf', 1024, UNHEX(SHA2('FUNCTION-CONTRACT-C-V1', 256)), @accepted_at
    ),
    (
        @functional_c_document_id, 2, 'SIGNED',
        CONCAT('contracts/', @functional_c_id, '/', @functional_c_document_id, '/v2.pdf'),
        'application/pdf', 1024, UNHEX(SHA2('FUNCTION-CONTRACT-C-V2', 256)), @accepted_at
    );

INSERT INTO escrows (work_case_id, amount, status, held_at, created_at, updated_at)
VALUES
    (@functional_b_id, @demo_wage, 'HELD', @accepted_at, @accepted_at, @accepted_at),
    (@functional_c_id, @demo_wage, 'HELD', @accepted_at, @accepted_at, @accepted_at);

SET @functional_b_escrow_id = (
    SELECT id FROM escrows WHERE work_case_id = @functional_b_id
);
SET @functional_c_escrow_id = (
    SELECT id FROM escrows WHERE work_case_id = @functional_c_id
);

INSERT INTO settlements (work_case_id, amount, status, created_at, updated_at)
VALUES
    (@functional_b_id, @demo_wage, 'WAITING', @accepted_at, @accepted_at),
    (@functional_c_id, @demo_wage, 'WAITING', @accepted_at, @accepted_at);

SET @owner_total = @owner_available + @owner_locked;

INSERT INTO wallet_transactions (
    wallet_id, work_case_id, transaction_type, amount,
    available_before, available_after, locked_before, locked_after,
    reference_type, reference_id, idempotency_key, created_at
) VALUES
    (
        @owner_wallet_id, NULL, 'FUNDING', @owner_total,
        0, @owner_total, 0, 0,
        'DEMO_SEED', @owner_id, 'FUNCTION-OWNER-FUNDING', DATE_SUB(@accepted_at, INTERVAL 1 MINUTE)
    ),
    (
        @owner_wallet_id, @functional_b_id, 'ESCROW_HOLD', @demo_wage,
        @owner_total, @owner_total - @demo_wage, 0, @demo_wage,
        'ESCROW', @functional_b_escrow_id, 'FUNCTION-B-HOLD', @accepted_at
    ),
    (
        @owner_wallet_id, @functional_c_id, 'ESCROW_HOLD', @demo_wage,
        @owner_total - @demo_wage, @owner_total - (@demo_wage * 2),
        @demo_wage, @demo_wage * 2,
        'ESCROW', @functional_c_escrow_id, 'FUNCTION-C-HOLD', DATE_ADD(@accepted_at, INTERVAL 1 SECOND)
    );

INSERT INTO notifications (
    recipient_user_id, noti_type, source_type, source_id,
    work_case_id, title, content, is_read, created_at
) VALUES
    (@owner_id, 'WORK_CASE_CONFIRMED', 'WORK_CASE', @functional_b_id,
     @functional_b_id, '근무 확정', '''[FUNCTION] 이수면 지각 출석체크'' 근무가 확정됐어요.', 0, @accepted_at),
    (@worker_b_id, 'WORK_CASE_CONFIRMED', 'WORK_CASE', @functional_b_id,
     @functional_b_id, '근무 확정', '''[FUNCTION] 이수면 지각 출석체크'' 근무가 확정됐어요.', 0, @accepted_at),
    (@owner_id, 'ESCROW_HELD', 'ESCROW', @functional_b_escrow_id,
     @functional_b_id, '예치 완료', '''[FUNCTION] 이수면 지각 출석체크'' 근무의 임금이 안전하게 예치됐어요.', 0, @accepted_at),
    (@worker_b_id, 'ESCROW_HELD', 'ESCROW', @functional_b_escrow_id,
     @functional_b_id, '예치 완료', '''[FUNCTION] 이수면 지각 출석체크'' 근무의 임금이 안전하게 예치됐어요.', 0, @accepted_at),
    (@owner_id, 'WORK_CASE_CONFIRMED', 'WORK_CASE', @functional_c_id,
     @functional_c_id, '근무 확정', '''[FUNCTION] 박잠수 자동 노쇼'' 근무가 확정됐어요.', 0, DATE_ADD(@accepted_at, INTERVAL 1 SECOND)),
    (@worker_c_id, 'WORK_CASE_CONFIRMED', 'WORK_CASE', @functional_c_id,
     @functional_c_id, '근무 확정', '''[FUNCTION] 박잠수 자동 노쇼'' 근무가 확정됐어요.', 0, DATE_ADD(@accepted_at, INTERVAL 1 SECOND)),
    (@owner_id, 'ESCROW_HELD', 'ESCROW', @functional_c_escrow_id,
     @functional_c_id, '예치 완료', '''[FUNCTION] 박잠수 자동 노쇼'' 근무의 임금이 안전하게 예치됐어요.', 0, DATE_ADD(@accepted_at, INTERVAL 1 SECOND)),
    (@worker_c_id, 'ESCROW_HELD', 'ESCROW', @functional_c_escrow_id,
     @functional_c_id, '예치 완료', '''[FUNCTION] 박잠수 자동 노쇼'' 근무의 임금이 안전하게 예치됐어요.', 0, DATE_ADD(@accepted_at, INTERVAL 1 SECOND));

-- 캘린더/목록 전환을 확인하는 상대 날짜 일정입니다.
INSERT INTO work_cases (
    employer_id, worker_id, workplace_id, title,
    starts_at, ends_at, break_minutes, break_paid,
    workplace_name, workplace_address,
    workplace_latitude, workplace_longitude, allowed_radius_meters,
    agreed_wage, terms_version, status, created_at, updated_at
) VALUES
    (
        @owner_id, NULL, @workplace_id, '[FUNCTION] 오늘 저녁 포장 지원',
        DATE_ADD(@seed_now, INTERVAL 5 HOUR), DATE_ADD(@seed_now, INTERVAL 9 HOUR),
        30, 0, '냠냠과자점 1호점', '서울 광진구 능동로 195-16',
        37.5481384, 127.0733972, 100.00, @demo_wage, 1, 'DRAFT', @seed_now, @seed_now
    ),
    (
        @owner_id, NULL, @workplace_id, '[FUNCTION] 내일 오픈 지원',
        DATE_ADD(@seed_now, INTERVAL 1 DAY), DATE_ADD(DATE_ADD(@seed_now, INTERVAL 1 DAY), INTERVAL 4 HOUR),
        30, 0, '냠냠과자점 1호점', '서울 광진구 능동로 195-16',
        37.5481384, 127.0733972, 100.00, @demo_wage, 1, 'DRAFT', @seed_now, @seed_now
    ),
    (
        @owner_id, NULL, @workplace_id, '[FUNCTION] 다음 주 팝업 지원',
        DATE_ADD(@seed_now, INTERVAL 7 DAY), DATE_ADD(DATE_ADD(@seed_now, INTERVAL 7 DAY), INTERVAL 4 HOUR),
        30, 0, '냠냠과자점 1호점', '서울 광진구 능동로 195-16',
        37.5481384, 127.0733972, 100.00, @demo_wage, 1, 'DRAFT', @seed_now, @seed_now
    );

COMMIT;

SELECT
    'functional' AS scenario_key,
    @seed_now AS seed_now,
    @workplace_id AS workplace_id,
    @functional_b_id AS attendance_work_case_id,
    DATE_ADD(@b_start, INTERVAL 1 HOUR) AS late_no_show_at,
    @functional_c_id AS no_show_work_case_id,
    DATE_ADD(@c_start, INTERVAL 1 HOUR) AS no_show_at,
    @owner_available AS owner_available_balance,
    @owner_locked AS owner_locked_balance,
    'gigsajang' AS owner_login_id,
    'hardworker' AS contract_worker_login_id,
    'ilovesleep' AS invitation_worker_login_id,
    'Demo1234!' AS demo_password;
