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
     * 지금 출퇴근을 처리할 활성 근무 후보를 찾습니다.
     *
     * <p>시간창 경계는 {@link com.gighub.attendance.domain.AttendanceWindowPolicy}가 판정
     * 시각으로부터 계산해 넘깁니다. 결과의 {@code scanType}도 같은 SQL이 정합니다.</p>
     *
     * @return 최대 2건. 2건이면 호출부가 후보 복수로 거절합니다
     */
    List<AttendanceScanCandidateRow> findActiveScanCandidates(
            @Param("workerId") long workerId,
            @Param("workplaceId") long workplaceId,
            @Param("readyLatestStartsAt") LocalDateTime readyLatestStartsAt,
            @Param("readyEarliestStartsAt") LocalDateTime readyEarliestStartsAt,
            @Param("checkOutEarliestEndsAt") LocalDateTime checkOutEarliestEndsAt);

    /**
     * 활성 후보가 없을 때 같은 시간 범위의 완료 후보 수를 셉니다.
     *
     * <p>"이미 완료"와 "근무 없음"을 가르는 값이라 최대 2까지만 셉니다.</p>
     */
    int countCompletedScanCandidates(
            @Param("workerId") long workerId,
            @Param("workplaceId") long workplaceId,
            @Param("readyLatestStartsAt") LocalDateTime readyLatestStartsAt,
            @Param("checkOutEarliestEndsAt") LocalDateTime checkOutEarliestEndsAt);

    /** 근무 한 건의 성공 CHECK_IN/CHECK_OUT 판정 시각을 함께 읽습니다. */
    AttendanceSuccessTimestampsRow findSuccessTimestamps(@Param("workCaseId") long workCaseId);

    /**
     * 근태 시도 한 건을 저장합니다.
     *
     * <p>{@code result='SUCCESS'}인 두 번째 시도는 {@code uk_attendance_records_success}가
     * {@code DuplicateKeyException}으로 막습니다. 호출부가 승인된 충돌 응답으로 바꿉니다.</p>
     */
    int insertAttempt(AttendanceRecordInsertParam param);
}
