package com.gighub.settlement.review;

import java.util.regex.Pattern;

/** 자유 입력에서 식별 가능한 전화·계좌 형태를 외부 전송 전에 제거합니다. */
public final class DisputeReviewRedactor {

    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?82[- ]?1[016789]|01[016789])[- ]?\\d{3,4}[- ]?\\d{4}(?!\\d)"
    );
    private static final Pattern LONG_FINANCIAL_NUMBER = Pattern.compile(
            "(?<!\\d)(?:\\d[- ]?){8,16}(?!\\d)"
    );

    private DisputeReviewRedactor() {
    }

    public static String redact(String value) {
        if (value == null) {
            return "";
        }
        String withoutPhones = PHONE.matcher(value).replaceAll("[REDACTED_PHONE]");
        return LONG_FINANCIAL_NUMBER.matcher(withoutPhones)
                .replaceAll("[REDACTED_FINANCIAL_NUMBER]");
    }
}
