package com.gighub.attendance.mapper;

import java.time.LocalDateTime;
import java.util.List;

import com.gighub.attendance.mapper.param.AttendanceRecordInsertParam;
import com.gighub.attendance.mapper.result.AttendanceScanCandidateRow;
import com.gighub.attendance.mapper.result.AttendanceSuccessTimestampsRow;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code attendance_records}의 단일 writer이자 스캔 대상 근무 조회 SQL 진입점입니다.
 *
 * <p>{@code work_cases}는 여기서 읽기만 합니다. 잠금과 상태 전이는 항상
 * {@link com.gighub.work.service.WorkLifecycleCommandService}를 거칩니다.</p>
 */
@Mapper
public interface AttendanceRecordMapper {

    /**
     * 로그인 WORKER와 QR이 가리키는 사업장에서 지금 처리 대상인 근무 후보를 찾습니다.
     *
     * <p>{@code idx_work_cases_worker_workplace_status_start_end}를 타는 조회입니다. 시간창
     * 경계는 {@link com.gighub.attendance.domain.AttendanceWindowPolicy}가 계산해 넘깁니다.</p>
     *
     * <p>잠금은 걸지 않으므로 호출부는 후보가 정확히 한 건일 때만 이 결과의
     * {@code workCaseId}로 {@link com.gighub.work.service.WorkLifecycleCommandService#lock}을
     * 부른 뒤 다시 검증합니다.</p>
     *
     * @return 최대 2건. 2건이면 호출부가 후보 복수로 거절합니다
     */
    List<AttendanceScanCandidateRow> findScanCandidates(
            @Param("workerId") long workerId,
            @Param("workplaceId") long workplaceId,
            @Param("latestStartsAt") LocalDateTime latestStartsAt,
            @Param("earliestEndsAt") LocalDateTime earliestEndsAt);

    /** 근무 한 건의 성공 CHECK_IN/CHECK_OUT 시각을 함께 읽습니다. */
    AttendanceSuccessTimestampsRow findSuccessTimestamps(@Param("workCaseId") long workCaseId);

    /**
     * 근태 시도 한 건을 저장합니다.
     *
     * <p>{@code result='SUCCESS'}인 두 번째 시도는 {@code uk_attendance_records_success}가
     * {@code DuplicateKeyException}으로 막습니다. 호출부가 승인된 충돌 응답으로 바꿉니다.</p>
     */
    int insertAttempt(AttendanceRecordInsertParam param);
}
