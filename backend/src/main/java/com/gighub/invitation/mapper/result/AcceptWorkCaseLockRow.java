package com.gighub.invitation.mapper.result;

import com.gighub.work.domain.WorkCaseStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 수락 직전에 잠근 근무 행입니다.
 *
 * <p>발급용 {@link InvitationWorkCaseLockRow}보다 넓습니다. 수락은 상태 판정뿐 아니라 계약
 * Snapshot과 예치 금액까지 같은 잠금 아래에서 확정해야 하므로, 조건이 바뀔 수 있는 값을 이
 * 조회 한 번에 모두 읽습니다. 나눠 읽으면 잠금 사이에 조건이 바뀌어 계약서와 실제 근무가
 * 어긋날 수 있습니다.</p>
 *
 * <p>좌표와 인증 반경은 응답에 나가지 않지만 계약 Snapshot에는 포함되므로 함께 읽습니다.</p>
 */
@Getter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AcceptWorkCaseLockRow {

    private Long workCaseId;
    private Long employerId;
    private Long workerId;
    private WorkCaseStatus status;
    private Integer termsVersion;
    private String title;
    private LocalDateTime startsAt;
    private LocalDateTime endsAt;
    private Integer breakMinutes;
    private Boolean breakPaid;
    private Long dailyWage;
    private String workplaceName;
    private String workplaceAddress;
    private BigDecimal workplaceLatitude;
    private BigDecimal workplaceLongitude;
    private BigDecimal allowedRadiusMeters;
}
