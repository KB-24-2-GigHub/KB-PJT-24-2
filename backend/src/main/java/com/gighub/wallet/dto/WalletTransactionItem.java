package com.gighub.wallet.dto;

import com.gighub.common.api.ApiTimes;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 지갑 거래 내역 목록의 Item 응답입니다.
 *
 * <p>{@code createdAt}은 DB의 {@code Asia/Seoul} 벽시계 값을 API 경계에서 UTC
 * {@link Instant}로 변환한 값입니다. {@code direction}과 {@code displayStatus}는 원장
 * 전후 잔액과 거래 유형에서 파생하며 저장 값이 아닙니다.</p>
 */
@Value
@Builder
public class WalletTransactionItem {

    Long transactionId;
    String type;
    Long amount;
    String direction;
    Long availableAfter;
    Long lockedAfter;
    Long workCaseId;
    String workTitle;
    String workplaceName;
    String displayStatus;
    Instant createdAt;

    public static WalletTransactionItem of(
            Long transactionId,
            String type,
            Long amount,
            String direction,
            Long availableAfter,
            Long lockedAfter,
            Long workCaseId,
            String workTitle,
            String workplaceName,
            String displayStatus,
            LocalDateTime createdAt) {
        return WalletTransactionItem.builder()
                .transactionId(transactionId)
                .type(type)
                .amount(amount)
                .direction(direction)
                .availableAfter(availableAfter)
                .lockedAfter(lockedAfter)
                .workCaseId(workCaseId)
                .workTitle(workTitle)
                .workplaceName(workplaceName)
                .displayStatus(displayStatus)
                .createdAt(ApiTimes.toInstant(createdAt))
                .build();
    }
}
