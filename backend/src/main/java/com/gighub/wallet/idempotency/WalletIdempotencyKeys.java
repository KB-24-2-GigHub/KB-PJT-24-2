package com.gighub.wallet.idempotency;

import com.gighub.wallet.exception.InvalidIdempotencyKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

public final class WalletIdempotencyKeys {

    private static final int MAX_RAW_KEY_LENGTH = 100;
    private static final Pattern VISIBLE_ASCII =
            Pattern.compile("\\A[\\x21-\\x7E]{1," + MAX_RAW_KEY_LENGTH + "}\\z");

    private WalletIdempotencyKeys() {
    }

    public static String funding(String rawKey) {
        return encode("FUND", rawKey);
    }

    public static String escrowHold(String rawKey) {
        return encode("EHLD", rawKey);
    }

    public static String settlementReleaseOwner(long settlementId) {
        return encodeSettlement("SETTLEMENT_RELEASE_OWNER", settlementId);
    }

    public static String settlementReleaseWorker(long settlementId) {
        return encodeSettlement("SETTLEMENT_RELEASE_WORKER", settlementId);
    }

    public static String withdrawal(String rawKey) {
        return encode("WDRW", rawKey);
    }

    public static String validateRawKey(String rawKey) {
        if (rawKey == null || !VISIBLE_ASCII.matcher(rawKey).matches()) {
            throw new InvalidIdempotencyKeyException(
                    "멱등 키는 1~100자의 공백 없는 출력 가능한 ASCII 문자열이어야 합니다."
            );
        }
        return rawKey;
    }

    private static String encode(String scope, String rawKey) {
        String validatedKey = validateRawKey(rawKey);
        String source = scope + '\0' + validatedKey;
        return scope + ':' + sha256(source);
    }

    private static String encodeSettlement(String namespace, long settlementId) {
        if (settlementId <= 0) {
            throw new IllegalArgumentException("정산 식별자는 양수여야 합니다.");
        }
        String source = namespace + '\0' + settlementId;
        return namespace + ':' + sha256(source);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(value.getBytes(StandardCharsets.US_ASCII))
            );
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }
}
