package com.gighub.wallet.idempotency;

import com.gighub.wallet.exception.InvalidIdempotencyKeyException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletIdempotencyKeysTest {

    private static final String RAW_KEY = "shared-key-001";

    @Test
    @DisplayName("외부 Key 범위와 Settlement ID 기반 원장 Key가 서로 충돌하지 않는다")
    void createsDistinctFixedLengthKeysByScope() {
        Set<String> keys = Set.of(
                WalletIdempotencyKeys.funding(RAW_KEY),
                WalletIdempotencyKeys.escrowHold(RAW_KEY),
                WalletIdempotencyKeys.settlementReleaseOwner(17L),
                WalletIdempotencyKeys.settlementReleaseWorker(17L),
                WalletIdempotencyKeys.withdrawal(RAW_KEY)
        );

        assertEquals(5, keys.size());
        keys.forEach(key -> {
            assertEquals(key.length(), key.getBytes(StandardCharsets.US_ASCII).length);
            assertTrue(key.matches("[A-Z_]+:[0-9a-f]{64}"));
        });
    }

    @Test
    @DisplayName("같은 Settlement ID는 외부 요청 Key와 무관한 결정적 양측 원장 Key를 만든다")
    void createsDeterministicSettlementKeys() {
        assertEquals(
                WalletIdempotencyKeys.settlementReleaseOwner(17L),
                WalletIdempotencyKeys.settlementReleaseOwner(17L));
        assertEquals(
                WalletIdempotencyKeys.settlementReleaseWorker(17L),
                WalletIdempotencyKeys.settlementReleaseWorker(17L));
        assertTrue(WalletIdempotencyKeys.settlementReleaseOwner(17L)
                .startsWith("SETTLEMENT_RELEASE_OWNER:"));
        assertTrue(WalletIdempotencyKeys.settlementReleaseWorker(17L)
                .startsWith("SETTLEMENT_RELEASE_WORKER:"));
        assertThrows(
                IllegalArgumentException.class,
                () -> WalletIdempotencyKeys.settlementReleaseOwner(0L));
    }

    @Test
    @DisplayName("원문이 다른 범위의 인코딩 형태를 흉내 내도 다시 안전하게 해시한다")
    void hashesPrefixLikeRawKeyAgain() {
        String prefixLikeRawKey = WalletIdempotencyKeys.funding(RAW_KEY);

        assertTrue(
                !prefixLikeRawKey.equals(
                        WalletIdempotencyKeys.escrowHold(prefixLikeRawKey)
                )
        );
    }

    @Test
    @DisplayName("빈 값, 공백, 비 ASCII, 100자 초과 키를 거부한다")
    void rejectsInvalidRawKeys() {
        assertThrows(
                InvalidIdempotencyKeyException.class,
                () -> WalletIdempotencyKeys.funding("")
        );
        assertThrows(
                InvalidIdempotencyKeyException.class,
                () -> WalletIdempotencyKeys.funding("contains space")
        );
        assertThrows(
                InvalidIdempotencyKeyException.class,
                () -> WalletIdempotencyKeys.funding("한글")
        );
        assertThrows(
                InvalidIdempotencyKeyException.class,
                () -> WalletIdempotencyKeys.funding("a".repeat(101))
        );
    }
}
