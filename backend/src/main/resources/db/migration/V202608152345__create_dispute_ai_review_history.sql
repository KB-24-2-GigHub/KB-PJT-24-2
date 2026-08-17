-- #390 LLM 외부 분쟁조정 DEMO의 결과와 실행 이력을 분쟁 본문과 분리해 보존한다.
-- API Key, 원문 사용자·계좌 식별자와 내부 Work/Settlement ID는 이 Table에 저장하지 않는다.
CREATE TABLE dispute_ai_reviews (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    dispute_id BIGINT UNSIGNED NOT NULL,
    request_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(20) NOT NULL,
    source VARCHAR(30) NOT NULL,
    provider VARCHAR(30) NOT NULL,
    model VARCHAR(100) NOT NULL,
    prompt_version VARCHAR(50) NOT NULL,
    input_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    decision VARCHAR(30) NULL,
    reason_codes JSON NULL,
    summary VARCHAR(500) NULL,
    confidence DECIMAL(4, 3) NULL,
    provider_response_id VARCHAR(100) NULL,
    failure_code VARCHAR(50) NULL,
    lease_until DATETIME(6) NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    active_slot TINYINT GENERATED ALWAYS AS (
        CASE WHEN status IN ('PENDING', 'PROCESSING') THEN 1 ELSE NULL END
    ) STORED,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_dispute_ai_reviews_request_key (request_key),
    UNIQUE KEY uk_dispute_ai_reviews_active (dispute_id, active_slot),
    KEY idx_dispute_ai_reviews_status_lease (status, lease_until, id),
    KEY idx_dispute_ai_reviews_dispute_created (dispute_id, created_at, id),
    CONSTRAINT fk_dispute_ai_reviews_dispute
        FOREIGN KEY (dispute_id) REFERENCES disputes (id)
        ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT ck_dispute_ai_reviews_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED')
    ),
    CONSTRAINT ck_dispute_ai_reviews_source CHECK (source = 'SIMULATED_LLM'),
    CONSTRAINT ck_dispute_ai_reviews_request_key CHECK (
        request_key REGEXP
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_dispute_ai_reviews_provider CHECK (
        provider = TRIM(provider) AND CHAR_LENGTH(provider) BETWEEN 1 AND 30
    ),
    CONSTRAINT ck_dispute_ai_reviews_model CHECK (
        model = TRIM(model) AND CHAR_LENGTH(model) BETWEEN 1 AND 100
    ),
    CONSTRAINT ck_dispute_ai_reviews_prompt_version CHECK (
        prompt_version = TRIM(prompt_version)
        AND CHAR_LENGTH(prompt_version) BETWEEN 1 AND 50
    ),
    CONSTRAINT ck_dispute_ai_reviews_input_hash CHECK (
        input_hash REGEXP '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_dispute_ai_reviews_decision CHECK (
        decision IS NULL OR decision IN ('RESOLVE', 'REJECT', 'NEEDS_MORE_INFO')
    ),
    CONSTRAINT ck_dispute_ai_reviews_reason_codes CHECK (
        reason_codes IS NULL
        OR (JSON_TYPE(reason_codes) = 'ARRAY' AND JSON_LENGTH(reason_codes) BETWEEN 1 AND 5)
    ),
    CONSTRAINT ck_dispute_ai_reviews_confidence CHECK (
        confidence IS NULL OR confidence BETWEEN 0.000 AND 1.000
    ),
    CONSTRAINT ck_dispute_ai_reviews_lifecycle CHECK (
        (
            status = 'PENDING'
            AND decision IS NULL
            AND reason_codes IS NULL
            AND summary IS NULL
            AND confidence IS NULL
            AND provider_response_id IS NULL
            AND failure_code IS NULL
            AND lease_until IS NULL
            AND started_at IS NULL
            AND completed_at IS NULL
        ) OR (
            status = 'PROCESSING'
            AND decision IS NULL
            AND reason_codes IS NULL
            AND summary IS NULL
            AND confidence IS NULL
            AND provider_response_id IS NULL
            AND failure_code IS NULL
            AND lease_until IS NOT NULL
            AND started_at IS NOT NULL
            AND completed_at IS NULL
        ) OR (
            status = 'COMPLETED'
            AND decision IS NOT NULL
            AND reason_codes IS NOT NULL
            AND summary = TRIM(summary)
            AND CHAR_LENGTH(summary) BETWEEN 1 AND 500
            AND confidence IS NOT NULL
            AND provider_response_id IS NOT NULL
            AND failure_code IS NULL
            AND lease_until IS NULL
            AND started_at IS NOT NULL
            AND completed_at IS NOT NULL
            AND completed_at >= started_at
        ) OR (
            status = 'FAILED'
            AND decision IS NULL
            AND reason_codes IS NULL
            AND summary IS NULL
            AND confidence IS NULL
            AND provider_response_id IS NULL
            AND failure_code = TRIM(failure_code)
            AND CHAR_LENGTH(failure_code) BETWEEN 1 AND 50
            AND lease_until IS NULL
            AND started_at IS NOT NULL
            AND completed_at IS NOT NULL
            AND completed_at >= started_at
        )
    )
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;
