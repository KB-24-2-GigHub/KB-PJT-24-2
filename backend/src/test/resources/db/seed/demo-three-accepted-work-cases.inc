-- 호출 SQL이 @a_start/@a_end/@a_status, @b_*, @c_*, @demo_wage를 설정합니다.
SET @accepted_at = DATE_SUB(@seed_now, INTERVAL 1 DAY);

INSERT INTO work_cases (
    employer_id, worker_id, workplace_id, title,
    starts_at, ends_at, break_minutes, break_paid,
    workplace_name, workplace_address,
    workplace_latitude, workplace_longitude, allowed_radius_meters,
    agreed_wage, terms_version, status, created_at, updated_at
) VALUES (
    @owner_id, @worker_a_id, @workplace_id, '[DEMO] 김성실 정상 근무',
    @a_start, @a_end, 30, 0,
    '냠냠과자점 1호점', '서울 광진구 능동로 195-16',
    37.5481384, 127.0733972, @demo_radius_meters,
    @demo_wage, 1, @a_status, @accepted_at, @seed_now
);
SET @work_case_a_id = LAST_INSERT_ID();

INSERT INTO work_cases (
    employer_id, worker_id, workplace_id, title,
    starts_at, ends_at, break_minutes, break_paid,
    workplace_name, workplace_address,
    workplace_latitude, workplace_longitude, allowed_radius_meters,
    agreed_wage, terms_version, status, created_at, updated_at
) VALUES (
    @owner_id, @worker_b_id, @workplace_id, '[DEMO] 이수면 30분 지각 근무',
    @b_start, @b_end, 30, 0,
    '냠냠과자점 1호점', '서울 광진구 능동로 195-16',
    37.5481384, 127.0733972, @demo_radius_meters,
    @demo_wage, 1, @b_status, @accepted_at, @seed_now
);
SET @work_case_b_id = LAST_INSERT_ID();

INSERT INTO work_cases (
    employer_id, worker_id, workplace_id, title,
    starts_at, ends_at, break_minutes, break_paid,
    workplace_name, workplace_address,
    workplace_latitude, workplace_longitude, allowed_radius_meters,
    agreed_wage, terms_version, status, created_at, updated_at
) VALUES (
    @owner_id, @worker_c_id, @workplace_id, '[DEMO] 박잠수 노쇼 근무',
    @c_start, @c_end, 30, 0,
    '냠냠과자점 1호점', '서울 광진구 능동로 195-16',
    37.5481384, 127.0733972, @demo_radius_meters,
    @demo_wage, 1, @c_status, @accepted_at, @seed_now
);
SET @work_case_c_id = LAST_INSERT_ID();

INSERT INTO work_invitations (
    work_case_id, token_hash, status, expected_terms_version,
    expires_at, accepted_by_user_id, accepted_terms_version, accepted_at, created_at
) VALUES
    (
        @work_case_a_id, UNHEX(SHA2('DEMO-ACCEPTED-A', 256)), 'ACCEPTED', 1,
        @a_start, @worker_a_id, 1, @accepted_at, @accepted_at
    ),
    (
        @work_case_b_id, UNHEX(SHA2('DEMO-ACCEPTED-B', 256)), 'ACCEPTED', 1,
        @b_start, @worker_b_id, 1, @accepted_at, @accepted_at
    ),
    (
        @work_case_c_id, UNHEX(SHA2('DEMO-ACCEPTED-C', 256)), 'ACCEPTED', 1,
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
        @work_case_a_id, @owner_id, @worker_a_id, '김성실 일일 근로계약서',
        @a_start, @a_end, 30, 0,
        '냠냠과자점 1호점', '서울 광진구 능동로 195-16',
        37.5481384, 127.0733972, @demo_radius_meters,
        @demo_wage, 1,
        JSON_OBJECT('demo', TRUE, 'persona', 'A', 'healthCertificateRequired', TRUE),
        @accepted_at, @accepted_at
    ),
    (
        @work_case_b_id, @owner_id, @worker_b_id, '이수면 일일 근로계약서',
        @b_start, @b_end, 30, 0,
        '냠냠과자점 1호점', '서울 광진구 능동로 195-16',
        37.5481384, 127.0733972, @demo_radius_meters,
        @demo_wage, 1,
        JSON_OBJECT('demo', TRUE, 'persona', 'B', 'plannedLateMinutes', 30),
        @accepted_at, @accepted_at
    ),
    (
        @work_case_c_id, @owner_id, @worker_c_id, '박잠수 일일 근로계약서',
        @c_start, @c_end, 30, 0,
        '냠냠과자점 1호점', '서울 광진구 능동로 195-16',
        37.5481384, 127.0733972, @demo_radius_meters,
        @demo_wage, 1,
        JSON_OBJECT('demo', TRUE, 'persona', 'C', 'plannedOutcome', 'NO_SHOW'),
        @accepted_at, @accepted_at
    );

-- 계약이 있으면 EMPLOYMENT_CONTRACT 문서도 함께 있어야 근무 상세를 열 수 있습니다. 계약만
-- 있고 문서가 없는 조합은 서버가 계약서 생성이 끊긴 손상 상태로 보고 예외를 던져 상세 조회가
-- 500이 됩니다(WorkCaseServiceImpl.requireContractIntegrity).
--
-- SQL은 실제 계약서 PDF를 문서 저장소에 만들 수 없으므로 파기(DELETED)로 넣습니다. 파기된
-- 문서는 documentId가 감춰져(WorkCaseServiceImpl.visibleDocumentId) 열리지 않는 '계약서 보기'
-- 링크를 노출하지 않고 문서함 목록에서도 빠집니다. 실제 PDF까지 있는 계약서 확인은 초대 수락
-- API로 만드는 demo-functional.sql의 김성실 근무가 담당합니다.
INSERT INTO documents (
    created_by_user_id, owner_user_id, work_case_id,
    document_type, status, issued_on, created_at, updated_at
) VALUES (
    @owner_id, @owner_id, @work_case_a_id,
    'EMPLOYMENT_CONTRACT', 'DELETED', DATE(@a_start), @accepted_at, @accepted_at
);
SET @contract_document_a_id = LAST_INSERT_ID();

INSERT INTO documents (
    created_by_user_id, owner_user_id, work_case_id,
    document_type, status, issued_on, created_at, updated_at
) VALUES (
    @owner_id, @owner_id, @work_case_b_id,
    'EMPLOYMENT_CONTRACT', 'DELETED', DATE(@b_start), @accepted_at, @accepted_at
);
SET @contract_document_b_id = LAST_INSERT_ID();

INSERT INTO documents (
    created_by_user_id, owner_user_id, work_case_id,
    document_type, status, issued_on, created_at, updated_at
) VALUES (
    @owner_id, @owner_id, @work_case_c_id,
    'EMPLOYMENT_CONTRACT', 'DELETED', DATE(@c_start), @accepted_at, @accepted_at
);
SET @contract_document_c_id = LAST_INSERT_ID();

-- 수락 한 번에 ORIGINAL(v1)과 서명본(v2)을 함께 만드는 실제 흐름과 같은 Version 구성입니다
-- (PdfContractArtifactPort). 파기는 Version 행을 지우지 않으므로 그대로 남겨 둡니다
-- (DocumentDeleteServiceImpl).
INSERT INTO document_versions (
    document_id, version_no, version_type, storage_key,
    mime_type, size_bytes, checksum, created_at
) VALUES
    (
        @contract_document_a_id, 1, 'ORIGINAL',
        CONCAT('contracts/', @work_case_a_id, '/', @contract_document_a_id, '/v1.pdf'),
        'application/pdf', 1024, UNHEX(SHA2('DEMO-CONTRACT-A-V1', 256)), @accepted_at
    ),
    (
        @contract_document_a_id, 2, 'SIGNED',
        CONCAT('contracts/', @work_case_a_id, '/', @contract_document_a_id, '/v2.pdf'),
        'application/pdf', 1024, UNHEX(SHA2('DEMO-CONTRACT-A-V2', 256)), @accepted_at
    ),
    (
        @contract_document_b_id, 1, 'ORIGINAL',
        CONCAT('contracts/', @work_case_b_id, '/', @contract_document_b_id, '/v1.pdf'),
        'application/pdf', 1024, UNHEX(SHA2('DEMO-CONTRACT-B-V1', 256)), @accepted_at
    ),
    (
        @contract_document_b_id, 2, 'SIGNED',
        CONCAT('contracts/', @work_case_b_id, '/', @contract_document_b_id, '/v2.pdf'),
        'application/pdf', 1024, UNHEX(SHA2('DEMO-CONTRACT-B-V2', 256)), @accepted_at
    ),
    (
        @contract_document_c_id, 1, 'ORIGINAL',
        CONCAT('contracts/', @work_case_c_id, '/', @contract_document_c_id, '/v1.pdf'),
        'application/pdf', 1024, UNHEX(SHA2('DEMO-CONTRACT-C-V1', 256)), @accepted_at
    ),
    (
        @contract_document_c_id, 2, 'SIGNED',
        CONCAT('contracts/', @work_case_c_id, '/', @contract_document_c_id, '/v2.pdf'),
        'application/pdf', 1024, UNHEX(SHA2('DEMO-CONTRACT-C-V2', 256)), @accepted_at
    );

INSERT INTO escrows (work_case_id, amount, status, held_at, created_at, updated_at)
VALUES
    (@work_case_a_id, @demo_wage, 'HELD', @accepted_at, @accepted_at, @accepted_at),
    (@work_case_b_id, @demo_wage, 'HELD', @accepted_at, @accepted_at, @accepted_at),
    (@work_case_c_id, @demo_wage, 'HELD', @accepted_at, @accepted_at, @accepted_at);

SET @escrow_a_id = (SELECT id FROM escrows WHERE work_case_id = @work_case_a_id);
SET @escrow_b_id = (SELECT id FROM escrows WHERE work_case_id = @work_case_b_id);
SET @escrow_c_id = (SELECT id FROM escrows WHERE work_case_id = @work_case_c_id);

INSERT INTO settlements (work_case_id, amount, status, created_at, updated_at)
VALUES
    (@work_case_a_id, @demo_wage, 'WAITING', @accepted_at, @accepted_at),
    (@work_case_b_id, @demo_wage, 'WAITING', @accepted_at, @accepted_at),
    (@work_case_c_id, @demo_wage, 'WAITING', @accepted_at, @accepted_at);

INSERT INTO notifications (
    recipient_user_id, noti_type, source_type, source_id,
    work_case_id, title, content, is_read, created_at
) VALUES
    (@owner_id, 'WORK_CASE_CONFIRMED', 'WORK_CASE', @work_case_a_id,
     @work_case_a_id, '근무 확정', '''[DEMO] 김성실 정상 근무'' 근무가 확정됐어요.', 0, @accepted_at),
    (@worker_a_id, 'WORK_CASE_CONFIRMED', 'WORK_CASE', @work_case_a_id,
     @work_case_a_id, '근무 확정', '''[DEMO] 김성실 정상 근무'' 근무가 확정됐어요.', 0, @accepted_at),
    (@owner_id, 'ESCROW_HELD', 'ESCROW', @escrow_a_id,
     @work_case_a_id, '예치 완료', '''[DEMO] 김성실 정상 근무'' 근무의 임금이 안전하게 예치됐어요.', 0, @accepted_at),
    (@worker_a_id, 'ESCROW_HELD', 'ESCROW', @escrow_a_id,
     @work_case_a_id, '예치 완료', '''[DEMO] 김성실 정상 근무'' 근무의 임금이 안전하게 예치됐어요.', 0, @accepted_at),
    (@owner_id, 'WORK_CASE_CONFIRMED', 'WORK_CASE', @work_case_b_id,
     @work_case_b_id, '근무 확정', '''[DEMO] 이수면 30분 지각 근무'' 근무가 확정됐어요.', 0, DATE_ADD(@accepted_at, INTERVAL 1 SECOND)),
    (@worker_b_id, 'WORK_CASE_CONFIRMED', 'WORK_CASE', @work_case_b_id,
     @work_case_b_id, '근무 확정', '''[DEMO] 이수면 30분 지각 근무'' 근무가 확정됐어요.', 0, DATE_ADD(@accepted_at, INTERVAL 1 SECOND)),
    (@owner_id, 'ESCROW_HELD', 'ESCROW', @escrow_b_id,
     @work_case_b_id, '예치 완료', '''[DEMO] 이수면 30분 지각 근무'' 근무의 임금이 안전하게 예치됐어요.', 0, DATE_ADD(@accepted_at, INTERVAL 1 SECOND)),
    (@worker_b_id, 'ESCROW_HELD', 'ESCROW', @escrow_b_id,
     @work_case_b_id, '예치 완료', '''[DEMO] 이수면 30분 지각 근무'' 근무의 임금이 안전하게 예치됐어요.', 0, DATE_ADD(@accepted_at, INTERVAL 1 SECOND)),
    (@owner_id, 'WORK_CASE_CONFIRMED', 'WORK_CASE', @work_case_c_id,
     @work_case_c_id, '근무 확정', '''[DEMO] 박잠수 노쇼 근무'' 근무가 확정됐어요.', 0, DATE_ADD(@accepted_at, INTERVAL 2 SECOND)),
    (@worker_c_id, 'WORK_CASE_CONFIRMED', 'WORK_CASE', @work_case_c_id,
     @work_case_c_id, '근무 확정', '''[DEMO] 박잠수 노쇼 근무'' 근무가 확정됐어요.', 0, DATE_ADD(@accepted_at, INTERVAL 2 SECOND)),
    (@owner_id, 'ESCROW_HELD', 'ESCROW', @escrow_c_id,
     @work_case_c_id, '예치 완료', '''[DEMO] 박잠수 노쇼 근무'' 근무의 임금이 안전하게 예치됐어요.', 0, DATE_ADD(@accepted_at, INTERVAL 2 SECOND)),
    (@worker_c_id, 'ESCROW_HELD', 'ESCROW', @escrow_c_id,
     @work_case_c_id, '예치 완료', '''[DEMO] 박잠수 노쇼 근무'' 근무의 임금이 안전하게 예치됐어요.', 0, DATE_ADD(@accepted_at, INTERVAL 2 SECOND));
