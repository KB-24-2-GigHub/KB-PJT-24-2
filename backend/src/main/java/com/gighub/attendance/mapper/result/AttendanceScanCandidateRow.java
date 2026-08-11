package com.gighub.attendance.mapper.result;

import java.time.LocalDateTime;

import com.gighub.attendance.domain.AttendanceType;
import com.gighub.work.domain.WorkCaseStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * QR 스캔 요청이 가리키는 처리 대상 근무 후보 한 건입니다.
 *
 * <p>{@code scanType}은 SQL이 상태와 성공 CHECK_IN 유무로 정한 값입니다. 활성 후보를 고르는
 * 조건과 유형을 정하는 조건이 같아야 하므로 Java에서 다시 계산하지 않습니다.</p>
 *
 * <p>좌표는 담지 않습니다. API_SPEC 6.0.0이 거리 판정 기준을 근무 생성 시점 Snapshot이 아니라
 * <b>현재 {@code workplaces} 좌표</b>로 고정했기 때문에, 좌표는 잠근 사업장 행에서 따로
 * 읽습니다. 두 곳이 각자 좌표를 들고 있으면 어느 쪽이 기준인지 알 수 없게 됩니다.</p>
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
    private final AttendanceType scanType;
}
