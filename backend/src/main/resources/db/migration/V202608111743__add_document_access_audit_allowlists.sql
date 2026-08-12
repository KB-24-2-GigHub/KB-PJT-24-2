-- #179 M7 문서 접근 감사 allowlist Schema 반영
-- SPEC-178-04가 확정한 감사 action과 거부 사유의 유한값을 DB에서도 고정한다.
-- 신규 Column·Index·테이블은 만들지 않고 기존 컬럼의 CHECK만 교체·추가한다.

-- 문서 접근 감사 action: 보건증·근로계약서 파일 view/download와 문서 상세 조회 다섯 종류.
ALTER TABLE document_access_logs
    ADD CONSTRAINT ck_document_access_logs_action CHECK (
        action IN (
            'HEALTH_CERT_FILE_VIEW', 'HEALTH_CERT_FILE_DOWNLOAD',
            'CONTRACT_FILE_VIEW', 'CONTRACT_FILE_DOWNLOAD',
            'DOCUMENT_DETAIL_VIEW'
        )
    ),
    -- 기존 "빈 문자열 아님" 조건을 승인된 거부 사유 목록으로 좁힌다.
    DROP CONSTRAINT ck_document_access_logs_denial_reason,
    ADD CONSTRAINT ck_document_access_logs_denial_reason CHECK (
        denial_reason IS NULL
        OR (
            result = 'DENIED'
            AND denial_reason IN (
                'PARTY_ACCESS_DENIED', 'DOCUMENT_UNAVAILABLE',
                'FILE_UNAVAILABLE', 'CHECKSUM_MISMATCH',
                'SIGNED_VERSION_UNAVAILABLE'
            )
        )
    );
