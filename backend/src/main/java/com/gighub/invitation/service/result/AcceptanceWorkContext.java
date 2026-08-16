package com.gighub.invitation.service.result;

import com.gighub.work.domain.WorkCaseStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 수락 outer Transaction이 Work participant에서 받은 persistence-free Snapshot입니다. */
@Getter
@Builder
public class AcceptanceWorkContext {

    private final long invitationId;
    private final long workCaseId;
    private final long employerId;
    private final Long workerId;
    private final WorkCaseStatus status;
    private final int termsVersion;
    private final String title;
    private final LocalDateTime startsAt;
    private final LocalDateTime endsAt;
    private final int breakMinutes;
    private final boolean breakPaid;
    private final long dailyWage;
    private final String workplaceName;
    private final String workplaceAddress;
    private final BigDecimal workplaceLatitude;
    private final BigDecimal workplaceLongitude;
    private final BigDecimal allowedRadiusMeters;
    private final LocalDateTime workCaseCreatedAt;
}
