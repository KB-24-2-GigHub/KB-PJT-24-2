package com.gighub.attendance.domain;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * 같은 스캔 의도인지 판정할 Fingerprint를 만듭니다.
 *
 * <p>API_SPEC 6.0.0이 입력과 순서를 고정했습니다. QR Token의 SHA-256 소문자 Hex, 후행 0을
 * 제거하고 {@code -0}을 {@code 0}으로 만든 지수 없는 위도·경도·정확도, UTC
 * {@code capturedAt}, 소문자 {@code confirmEarlyCheckout}을 차례로 LF로 이은 UTF-8 Bytes의
 * SHA-256입니다.</p>
 *
 * <p>Token 원문과 WORKER 정밀 좌표는 Hash 입력으로만 쓰고 결과에 복원 가능한 형태로 남기지
 * 않습니다. 정규화를 거치는 이유는 같은 값을 다르게 표기한 재시도({@code 37.50} vs
 * {@code 37.5})가 다른 요청으로 판정되지 않게 하기 위해서입니다.</p>
 */
public final class AttendanceScanFingerprint {

    private static final String SHA_256 = "SHA-256";
    private static final String SEPARATOR = "\n";

    private AttendanceScanFingerprint() {
    }

    public static byte[] of(
            String qrToken,
            BigDecimal latitude,
            BigDecimal longitude,
            BigDecimal accuracyMeters,
            Instant capturedAt,
            boolean confirmEarlyCheckout) {
        String source = HexFormat.of().formatHex(sha256(qrToken.getBytes(StandardCharsets.UTF_8)))
                + SEPARATOR + normalize(latitude)
                + SEPARATOR + normalize(longitude)
                + SEPARATOR + normalize(accuracyMeters)
                + SEPARATOR + capturedAt.toString()
                + SEPARATOR + Boolean.toString(confirmEarlyCheckout);
        return sha256(source.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 후행 0을 지우고 지수 표기를 쓰지 않는 십진 문자열로 만듭니다.
     *
     * <p>{@code stripTrailingZeros}는 {@code 100}을 {@code 1E+2}로 바꾸므로
     * {@code toPlainString}으로 되돌립니다. 부호 있는 0은 {@code -0}이 되어 같은 위치가 다른
     * 값으로 보이므로 {@code 0}으로 정규화합니다.</p>
     */
    private static String normalize(BigDecimal value) {
        String plain = value.stripTrailingZeros().toPlainString();
        return "-0".equals(plain) ? "0" : plain;
    }

    private static byte[] sha256(byte[] source) {
        try {
            return MessageDigest.getInstance(SHA_256).digest(source);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
