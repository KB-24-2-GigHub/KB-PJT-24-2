package com.gighub.attendance.mapper;

import com.gighub.attendance.mapper.result.AttendanceReadinessCheckRow;
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

    AttendanceReadinessCheckRow findReadinessCheck(@Param("workCaseId") long workCaseId);

    boolean hasSuccessfulAttendance(
            @Param("workCaseId") long workCaseId,
            @Param("attendanceType") String attendanceType);

}
