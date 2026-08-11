package com.gighub.attendance.mapper.result;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 근무 한 건의 성공 CHECK_IN/CHECK_OUT 시각입니다.
 *
 * <p>{@code uk_attendance_records_success}가 근무당 유형별 SUCCESS 행을 하나로 제한하므로
 * 이 조건부 집계는 근태 행이 없어도 두 필드가 모두 {@code null}인 행 하나를 돌려줍니다.</p>
 */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
public class AttendanceSuccessTimestampsRow {

    private final LocalDateTime checkedInAt;
    private final LocalDateTime checkedOutAt;
}
