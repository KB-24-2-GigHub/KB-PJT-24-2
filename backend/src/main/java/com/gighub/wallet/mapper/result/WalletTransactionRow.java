package com.gighub.wallet.mapper.result;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/** 지갑 거래 조회 SQL의 원장·근무 Snapshot 열을 보관합니다. */
@Getter
@Builder
@AllArgsConstructor
public final class WalletTransactionRow {

    private final Long transactionId;
    private final String type;
    private final Long amount;
    private final Long availableBefore;
    private final Long availableAfter;
    private final Long lockedBefore;
    private final Long lockedAfter;
    private final Long workCaseId;
    private final String workTitle;
    private final String workplaceName;
    private final LocalDateTime createdAt;
}
