package com.gighub.settlement.review;

import java.util.regex.Pattern;

/** 자유 입력에서 식별 가능한 이메일·전화·계좌 형태를 외부 전송 전에 제거합니다. */
public final class DisputeReviewRedactor {

    private static final Pattern EMAIL = Pattern.compile(
            "(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}"
                    + "(?![A-Z0-9._%+-])"
    );
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?82[- ]?1[016789]|01[016789])[- ]?\\d{3,4}[- ]?\\d{4}(?!\\d)"
    );
    private static final Pattern FORMATTED_FINANCIAL_NUMBER = Pattern.compile(
            "(?<!\\d)(?!20\\d{2}[- ]\\d{2}[- ]\\d{2}(?:[ T]\\d{2})?(?!\\d))"
                    + "(?=(?:\\d[- ]?){10,16}(?!\\d))"
                    + "\\d{2,6}(?:[- ]\\d{2,6}){2,3}(?!\\d)"
    );
    private static final Pattern CONTINUOUS_FINANCIAL_NUMBER = Pattern.compile(
            "(?<!\\d)(?!20\\d{8}(?!\\d))\\d{10,16}(?!\\d)"
    );

    private DisputeReviewRedactor() {
    }

    public static String redact(String value) {
        if (value == null) {
            return "";
        }
        String withoutEmails = EMAIL.matcher(value).replaceAll("[REDACTED_EMAIL]");
        String withoutPhones = PHONE.matcher(withoutEmails).replaceAll("[REDACTED_PHONE]");
        String withoutFormattedAccounts = FORMATTED_FINANCIAL_NUMBER.matcher(withoutPhones)
                .replaceAll("[REDACTED_FINANCIAL_NUMBER]");
        return CONTINUOUS_FINANCIAL_NUMBER.matcher(withoutFormattedAccounts)
                .replaceAll("[REDACTED_FINANCIAL_NUMBER]");
    }
}
