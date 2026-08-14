package com.gighub.attendance.qr;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Token 서명·검증 계약을 컨테이너 없이 검증합니다.
 *
 * <p>변조 검증은 떠오르는 금지 사례를 나열하지 않고 다섯 세그먼트를 하나씩 바꾼 전체
 * 집합을 단언합니다. 승인된 조합은 원본 하나뿐이라는 것이 계약입니다.</p>
 */
class QrTokenCodecTest {

    private static final byte[] KEY_A =
            "01234567890123456789012345678901".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY_B =
            "abcdefghijabcdefghijabcdefghijab".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] NONCE = {
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
    };

    private final QrTokenCodec codec = new QrTokenCodec(
            new QrHmacKeys("k1", Map.of("k1", KEY_A, "k0", KEY_B)));

    @Test
    void signIsDeterministicForTheSameWorkplaceAndNonce() {
        assertEquals(codec.sign(7L, NONCE), codec.sign(7L, NONCE));
    }

    @Test
    void signUsesFiveDotSeparatedSegmentsStartingWithVersionAndActiveKeyId() {
        String[] segments = codec.sign(7L, NONCE).split("\\.");

        assertEquals(5, segments.length);
        assertEquals("v1", segments[0]);
        assertEquals("k1", segments[1]);
        assertEquals("7", segments[2]);
    }

    @Test
    void verifyRecoversWorkplaceIdAndNonce() {
        Optional<QrTokenPayload> payload = codec.verify(codec.sign(7L, NONCE));

        assertTrue(payload.isPresent());
        assertEquals(7L, payload.get().workplaceId());
        assertArrayEquals(NONCE, payload.get().nonce());
    }

    @Test
    void differentWorkplacesProduceDifferentTokensForTheSameNonce() {
        assertNotEquals(codec.sign(7L, NONCE), codec.sign(8L, NONCE));
    }

    @Test
    void verifyAcceptsOnlyTheExactSignedToken() {
        String token = codec.sign(7L, NONCE);
        String[] parts = token.split("\\.");

        // 다섯 세그먼트를 하나씩 다른 유효 값으로 바꾼 전체 변형 집합입니다.
        List<String> tampered = List.of(
                join("v2", parts[1], parts[2], parts[3], parts[4]),
                join(parts[0], "k0", parts[2], parts[3], parts[4]),
                join(parts[0], parts[1], "8", parts[3], parts[4]),
                join(parts[0], parts[1], parts[2], otherNonce(), parts[4]),
                join(parts[0], parts[1], parts[2], parts[3], otherMac(parts[4]))
        );

        for (String candidate : tampered) {
            assertFalse(codec.verify(candidate).isPresent(), candidate);
        }
        assertTrue(codec.verify(token).isPresent());
    }

    /**
     * 벽에 붙은 QR은 만료되지 않으므로 키를 교체해도 계속 검증돼야 합니다. 교체 전 활성
     * 키로 서명한 Token을 교체 후 Codec으로 검증해, 등록만 유지하면 인쇄물이 살아 있음을
     * 확인합니다. 여기가 깨지면 매장에 부착된 QR이 한꺼번에 무효가 됩니다.
     */
    @Test
    void tokenSignedBeforeRotationStillVerifiesWhileItsKeyStaysRegistered() {
        QrTokenCodec beforeRotation = new QrTokenCodec(
                new QrHmacKeys("k0", Map.of("k0", KEY_B)));
        String printedToken = beforeRotation.sign(7L, NONCE);

        QrTokenCodec afterRotation = new QrTokenCodec(
                new QrHmacKeys("k1", Map.of("k1", KEY_A, "k0", KEY_B)));

        Optional<QrTokenPayload> verified = afterRotation.verify(printedToken);

        assertTrue(verified.isPresent());
        assertEquals(7L, verified.get().workplaceId());
        assertArrayEquals(NONCE, verified.get().nonce());
    }

    /**
     * 교체 목록에서 내린 키는 더 이상 검증되지 않아야 합니다. 폐기가 실제로 폐기여야
     * 유출된 구 키로 만든 Token을 막을 수 있습니다.
     */
    @Test
    void tokenSignedWithARetiredKeyIsRejectedOnceThatKeyIsUnregistered() {
        QrTokenCodec beforeRotation = new QrTokenCodec(
                new QrHmacKeys("k0", Map.of("k0", KEY_B)));
        String retiredToken = beforeRotation.sign(7L, NONCE);

        QrTokenCodec afterRetirement = new QrTokenCodec(
                new QrHmacKeys("k1", Map.of("k1", KEY_A)));

        assertFalse(afterRetirement.verify(retiredToken).isPresent());
    }

    @Test
    void verifyRejectsStructurallyInvalidTokens() {
        List<String> invalid = List.of(
                "",
                "v1",
                "v1.k1.7.nonce",
                "v1.k1.7.nonce.mac.extra",
                "v1.k1.notanumber.nonce.mac",
                "v1.unknown.7.nonce.mac"
        );

        for (String candidate : invalid) {
            assertFalse(codec.verify(candidate).isPresent(), candidate);
        }
    }

    private static String join(String... segments) {
        return String.join(".", segments);
    }

    private static String otherNonce() {
        byte[] other = NONCE.clone();
        other[0] ^= 0xFF;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(other);
    }

    private static String otherMac(String mac) {
        byte[] raw = Base64.getUrlDecoder().decode(mac);
        raw[0] ^= 0xFF;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
