-- 이 반경은 로컬·폐기 가능한 시연 Seed에서만 사용합니다. 일반 사업장 생성은 100m입니다.
SET @demo_radius_meters = 999999.00;

INSERT INTO workplaces (
    owner_user_id, business_registration_number, name,
    representative_name, road_address, detail_address, phone,
    latitude, longitude, radius_meters, status,
    created_at, updated_at
) VALUES (
    @owner_id, '2418157398', '냠냠과자점 1호점',
    '긱사장', '서울 광진구 능동로 195-16', NULL, '0215782468',
    37.5481384, 127.0733972, @demo_radius_meters, 'ACTIVE',
    @seed_now, @seed_now
);

SET @workplace_id = LAST_INSERT_ID();

-- 고정 QR 원문은 DB에 두지 않고 공개 nonce만 저장합니다. API가 운영 HMAC Key로 서명합니다.
INSERT INTO qr_tokens (
    workplace_id, issued_by_user_id, token_nonce, status, created_at
) VALUES (
    @workplace_id, @owner_id, RANDOM_BYTES(16), 'ACTIVE', @seed_now
);
