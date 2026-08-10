package com.gighub.attendance.mapper;

import com.gighub.attendance.mapper.result.AttendanceLifecycleWorkCaseRow;
import com.gighub.attendance.mapper.result.AttendanceReadinessCheckRow;
import com.gighub.work.domain.WorkCaseStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 근태 생명주기 Scheduler가 사용하는 후보 조회와 상태 전이 진입점입니다. */
@Mapper
public interface AttendanceLifecycleMapper {

    List<Long> findReadyCandidateIds(
            @Param("readyAt") LocalDateTime readyAt,
            @Param("noShowAfter") LocalDateTime noShowAfter,
            @Param("batchSize") int batchSize);

    List<Long> findNoShowCandidateIds(
            @Param("noShowAt") LocalDateTime noShowAt,
            @Param("batchSize") int batchSize);

    List<Long> findCheckoutMissingCandidateIds(
            @Param("checkoutMissingAt") LocalDateTime checkoutMissingAt,
            @Param("batchSize") int batchSize);

    /** 스캔과 판정이 같은 근무에 서로 다른 결과를 남기지 않도록 근무 행을 먼저 잠급니다. */
    AttendanceLifecycleWorkCaseRow lockById(@Param("workCaseId") long workCaseId);

    AttendanceReadinessCheckRow findReadinessCheck(@Param("workCaseId") long workCaseId);

    boolean hasSuccessfulAttendance(
            @Param("workCaseId") long workCaseId,
            @Param("attendanceType") String attendanceType);

    int transitionStatus(
            @Param("workCaseId") long workCaseId,
            @Param("expectedStatus") WorkCaseStatus expectedStatus,
            @Param("status") WorkCaseStatus status);
}
