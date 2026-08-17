package com.gighub.notification.domain;

import java.util.Objects;

/**
 * 승인된 알림 유형 6종과 각 유형이 가리키는 이벤트 종류·문구입니다(SPEC-382-01).
 *
 * <p>문구는 서버가 소유합니다. 클라이언트가 유형별로 문장을 조립하면 유형마다 파라미터 계약이
 * 따로 필요해지고, 같은 알림이 화면마다 다르게 읽힙니다. 여기서 완성한 문장을 저장하고 응답에
 * 그대로 싣습니다.</p>
 *
 * <p>{@code sourceType}과의 짝은 Database CHECK가 다시 강제합니다. 여기서 잘못 짝지으면
 * 저장 단계에서 거부되므로 두 곳이 조용히 어긋날 수 없습니다.</p>
 */
public enum NotificationType {

    WORK_CASE_CONFIRMED(
            NotificationSourceType.WORK_CASE,
            "근무 확정",
            "%s 근무가 확정됐어요."),
    ESCROW_HELD(
            NotificationSourceType.ESCROW,
            "예치 완료",
            "%s 근무의 임금이 안전하게 예치됐어요."),
    SETTLED(
            NotificationSourceType.SETTLEMENT,
            "정산 완료",
            "%s 근무의 정산이 완료됐어요."),
    REFUNDED(
            NotificationSourceType.SETTLEMENT,
            "노쇼 환불",
            "%s 근무의 예치금이 환불됐어요."),
    DOC_SHARED(
            NotificationSourceType.DOCUMENT_SHARE,
            "보건증 공유",
            "%s 근무에 보건증이 공유됐어요."),
    WAGE_REPORTED(
            NotificationSourceType.DISPUTE,
            "임금분쟁 신고",
            "%s 근무에 임금분쟁이 신고됐어요.");

    /** 저장 컬럼 길이와 같은 상한입니다. 근무 제목이 길면 문장이 아니라 제목만 줄입니다. */
    private static final int CONTENT_MAX_LENGTH = 500;
    private static final String ELLIPSIS = "…";

    private final NotificationSourceType sourceType;
    private final String title;
    private final String contentFormat;

    NotificationType(NotificationSourceType sourceType, String title, String contentFormat) {
        this.sourceType = sourceType;
        this.title = title;
        this.contentFormat = contentFormat;
    }

    public NotificationSourceType sourceType() {
        return sourceType;
    }

    public String title() {
        return title;
    }

    /**
     * 근무 제목을 넣어 저장할 문장을 만듭니다.
     *
     * <p>근무 제목은 사용자가 입력한 값이라 길이를 신뢰할 수 없습니다. 상한을 넘으면 저장이
     * 거부되고, 이 Patch의 계약상 알림 실패가 자금 처리를 되돌리지는 않지만 알림은 유실됩니다.
     * 문장 전체를 자르면 어미가 깨지므로 제목 쪽만 줄입니다.</p>
     */
    public String content(String workCaseTitle) {
        Objects.requireNonNull(workCaseTitle, "workCaseTitle");
        String quoted = "'" + workCaseTitle.trim() + "'";
        String rendered = contentFormat.formatted(quoted);
        if (rendered.length() <= CONTENT_MAX_LENGTH) {
            return rendered;
        }
        int overflow = rendered.length() - CONTENT_MAX_LENGTH + ELLIPSIS.length();
        String shortened = quoted.substring(0, Math.max(1, quoted.length() - overflow)) + ELLIPSIS;
        return contentFormat.formatted(shortened);
    }
}
