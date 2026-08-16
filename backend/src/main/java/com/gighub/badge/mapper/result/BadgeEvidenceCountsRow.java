package com.gighub.badge.mapper.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** OWNER/WORKER 원천 이력에서 집계한 누적·정상 건수입니다. */
@Getter
@AllArgsConstructor
public final class BadgeEvidenceCountsRow {

    private final long totalCount;
    private final long normalCount;
}