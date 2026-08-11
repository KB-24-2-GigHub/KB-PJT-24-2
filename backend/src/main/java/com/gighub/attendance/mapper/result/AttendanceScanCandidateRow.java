package com.gighub.attendance.mapper.result;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.gighub.work.domain.WorkCaseStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * QR 스캔 요청이 가리키는 처리 대상 근무 후보 한 건입니다.
 *
 * <p>{@code work_cases}의 좌표·반경 Snapshot은 현재 {@code workplaces} 값이 아니라 이
 * 행이 만들어질 때 고정된 값입니다. 스캔 검증은 이 Snapshot을 기준으로 삼습니다.</p>
 *
 * <p>MyBatis가 {@code <constructor>} 매핑으로 생성하므로 필드 선언 순서가 곧 생성자 인자
 * 순서입니다. XML과 함께 바꿔야 합니다.</p>
 */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
public class AttendanceScanCandidateRow {

    private final Long workCaseId;
    private final WorkCaseStatus status;
    private final LocalDateTime startsAt;
    private final LocalDateTime endsAt;
    private final BigDecimal workplaceLatitude;
    private final BigDecimal workplaceLongitude;
    private final BigDecimal allowedRadiusMeters;
}
