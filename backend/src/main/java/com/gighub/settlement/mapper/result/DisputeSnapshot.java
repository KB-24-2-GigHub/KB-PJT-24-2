package com.gighub.settlement.mapper.result;

import com.gighub.settlement.domain.DisputeStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 비동기 결과 반영 Transaction이 잠근 분쟁 Snapshot입니다. */
@Getter
@AllArgsConstructor
public class DisputeSnapshot {
    private final Long disputeId;
    private final Long workCaseId;
    private final String title;
    private final String content;
    private final DisputeStatus status;
}
