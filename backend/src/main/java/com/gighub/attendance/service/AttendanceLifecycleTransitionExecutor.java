package com.gighub.attendance.service;

import com.gighub.attendance.domain.AttendanceWindowPolicy;
import com.gighub.attendance.mapper.AttendanceLifecycleMapper;
import com.gighub.attendance.mapper.AttendanceRecordMapper;
import com.gighub.attendance.mapper.result.AttendanceReadinessCheckRow;
import com.gighub.attendance.mapper.result.AttendanceSuccessTimestampsRow;
import com.gighub.document.service.SignedContractArtifactQueryService;
import com.gighub.settlement.domain.SettlementCalculationReason;
import com.gighub.settlement.service.SettlementReservationService;
import com.gighub.settlement.service.command.SettlementCalculationCommand;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.service.WorkLifecycleCommandService;
import com.gighub.work.service.result.WorkLifecycleSnapshot;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 후보 근무 하나를 잠근 뒤 자동 상태 전이 조건을 다시 확인합니다. */
@Service
@RequiredArgsConstructor
public class AttendanceLifecycleTransitionExecutor {

    private static final Logger log =
            LoggerFactory.getLogger(AttendanceLifecycleTransitionExecutor.class);
    private static final String CHECK_IN = "CHECK_IN";
    private static final String CHECK_OUT = "CHECK_OUT";

    private final AttendanceLifecycleMapper lifecycleMapper;
    private final AttendanceRecordMapper attendanceRecordMapper;
    private final SignedContractArtifactQueryService artifactQueryService;
    private final WorkLifecycleCommandService workLifecycleCommandService;
    private final SettlementReservationService settlementReservationService;

    @Transactional
    public boolean advanceToReady(long workCaseId, LocalDateTime now) {
        WorkLifecycleSnapshot row = workLifecycleCommandService.lock(workCaseId);
        if (row == null
                || row.status() != WorkCaseStatus.ACCEPTED
                || AttendanceWindowPolicy.readyOpensAt(row.startsAt()).isAfter(now)
                || !now.isBefore(AttendanceWindowPolicy.noShowAt(
                        row.startsAt(), row.endsAt()))) {
            return false;
        }

        if (!isAttendanceReady(workCaseId)) {
            return false;
        }

        return transition(row, WorkCaseStatus.READY);
    }

    @Transactional
    public boolean advanceToNoShow(long workCaseId, LocalDateTime now) {
        WorkLifecycleSnapshot row = workLifecycleCommandService.lock(workCaseId);
        if (row == null
                || (row.status() != WorkCaseStatus.READY
                        && row.status() != WorkCaseStatus.ACCEPTED)
                || AttendanceWindowPolicy.noShowAt(
                        row.startsAt(), row.endsAt()).isAfter(now)
                || lifecycleMapper.hasSuccessfulAttendance(workCaseId, CHECK_IN)) {
            return false;
        }
        // Scheduler 지연으로 READY를 놓친 완전한 Aggregate만 ACCEPTED에서 직접 종료합니다.
        if (row.status() == WorkCaseStatus.ACCEPTED && !isAttendanceReady(workCaseId)) {
            return false;
        }
        if (!transition(row, WorkCaseStatus.NO_SHOW)) {
            return false;
        }
        settlementReservationService.recordTerminalSnapshot(
                terminalCommand(row, null, SettlementCalculationReason.NO_SHOW));
        return true;
    }

    @Transactional
    public boolean advanceToCheckoutMissing(long workCaseId, LocalDateTime now) {
        WorkLifecycleSnapshot row = workLifecycleCommandService.lock(workCaseId);
        if (row == null
                || row.status() != WorkCaseStatus.IN_PROGRESS
                || AttendanceWindowPolicy.checkOutMissingAt(row.endsAt()).isAfter(now)
                || !lifecycleMapper.hasSuccessfulAttendance(workCaseId, CHECK_IN)
                || lifecycleMapper.hasSuccessfulAttendance(workCaseId, CHECK_OUT)) {
            return false;
        }
        AttendanceSuccessTimestampsRow attendance =
                attendanceRecordMapper.findSuccessTimestamps(workCaseId);
        if (attendance == null
                || attendance.getCheckedInAt() == null
                || attendance.getCheckedOutAt() != null) {
            throw new IllegalStateException("퇴근 누락 정산의 성공 근태 기록이 올바르지 않습니다.");
        }
        if (!transition(row, WorkCaseStatus.CHECK_OUT_MISSING)) {
            return false;
        }
        settlementReservationService.recordTerminalSnapshot(terminalCommand(
                row,
                attendance.getCheckedInAt(),
                SettlementCalculationReason.CHECK_OUT_MISSING));
        return true;
    }

    private static SettlementCalculationCommand terminalCommand(
            WorkLifecycleSnapshot work,
            LocalDateTime checkedInAt,
            SettlementCalculationReason reason) {
        return SettlementCalculationCommand.builder()
                .workCaseId(work.workCaseId())
                .agreedWage(work.agreedWage())
                .startsAt(work.startsAt())
                .endsAt(work.endsAt())
                .breakMinutes(work.breakMinutes())
                .breakPaid(work.breakPaid())
                .checkedInAt(checkedInAt)
                .reason(reason)
                .build();
    }

    private boolean transition(
            WorkLifecycleSnapshot row,
            WorkCaseStatus target) {
        return workLifecycleCommandService.transition(
                row.workCaseId(), row.status(), target);
    }

    private boolean isAttendanceReady(long workCaseId) {
        AttendanceReadinessCheckRow readiness =
                lifecycleMapper.findReadinessCheck(workCaseId);
        if (readiness == null || !readiness.isComplete()) {
            auditReadyBlocked(workCaseId, readiness);
            return false;
        }
        if (!artifactQueryService.isReadable(workCaseId)) {
            auditReadyBlocked(workCaseId, List.of("SIGNED_CONTRACT_ARTIFACT_UNREADABLE"));
            return false;
        }
        return true;
    }

    private void auditReadyBlocked(
            long workCaseId,
            AttendanceReadinessCheckRow readiness) {
        List<String> reasons = readiness == null
                ? List.of("READINESS_ROW_MISSING")
                : readiness.failureReasons();
        auditReadyBlocked(workCaseId, reasons);
    }

    private void auditReadyBlocked(long workCaseId, List<String> reasons) {
        log.warn(
                "READY 전이 준비 조건이 충족되지 않았습니다. workCaseId={}, reasons={}",
                workCaseId,
                reasons);
    }
}
