-- #179 M7 신뢰 뱃지 유형 allowlist Schema 반영
-- SPEC-178-06이 확정한 뱃지 유형만 허용한다.
-- 등급·건수·문턱은 승인 계약대로 evidence JSON에 두고 신규 컬럼은 만들지 않는다.

ALTER TABLE user_badges
    ADD CONSTRAINT ck_user_badges_type CHECK (
        badge_type IN ('TRUST_OWNER', 'TRUST_WORKER')
    );
