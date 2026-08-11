package com.gighub.attendance.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;

import com.gighub.attendance.domain.AttendanceFailureReason;
import com.gighub.attendance.domain.AttendanceResult;
import com.gighub.attendance.domain.AttendanceScanOutcome;
import com.gighub.attendance.domain.AttendanceType;
import com.gighub.attendance.domain.AttendanceWindowPolicy;
import com.gighub.attendance.dto.AttendanceScanRequest;
import com.gighub.attendance.geo.HaversineDistanceCalculator;
import com.gighub.attendance.mapper.AttendanceRecordMapper;
import com.gighub.attendance.mapper.QrTokenMapper;
import com.gighub.attendance.mapper.param.AttendanceRecordInsertParam;
import com.gighub.attendance.mapper.result.AttendanceScanCandidateRow;
import com.gighub.attendance.mapper.result.AttendanceSuccessTimestampsRow;
import com.gighub.attendance.mapper.result.QrTokenRow;
import com.gighub.attendance.qr.QrTokenPayload;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.ConflictException;
import com.gighub.settlement.service.SettlementReservationService;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.service.WorkLifecycleCommandService;
import com.gighub.work.service.result.WorkLifecycleSnapshot;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 스캔 한 건의 DB 판정을 하나의 Transaction으로 처리합니다.
 *
 * <p>거절은 예외 대신 {@link AttendanceScanOutcome}으로 돌려줍니다. 거절 감사 행이 같은
 * Transaction에서 commit 되어야 하므로, 여기서 예외를 던지면 남기려던 기록이 함께
 * 되돌아갑니다. 사용자에게 보낼 오류로 바꾸는 일은 Transaction 밖의 호출부가 합니다.</p>
 *
 * <p>{@code work_cases}와 {@code settlements}는 각 모듈의 공개 명령으로만 바꿉니다. Wallet과
 * Escrow의 금액·원장은 이 Transaction에서 변경하지 않습니다.</p>
 */
@Service
public class AttendanceScanExecutor {

    private static final int DISTANCE_SCALE = 2;

    private final QrTokenMapper qrTokenMapper;
    private final AttendanceRecordMapper attendanceRecordMapper;
    private final WorkLifecycleCommandService workLifecycleCommandService;
    private final SettlementReservationService settlementReservationService;
    private final AttendanceScanAuditor scanAuditor;

    public AttendanceScanExecutor(
            QrTokenMapper qrTokenMapper,
            AttendanceRecordMapper attendanceRecordMapper,
            WorkLifecycleCommandService workLifecycleCommandService,
            SettlementReservationService settlementReservationService,
            AttendanceScanAuditor scanAuditor) {
        this.qrTokenMapper = qrTokenMapper;
        this.attendanceRecordMapper = attendanceRecordMapper;
        this.workLifecycleCommandService = workLifecycleCommandService;
        this.settlementReservationService = settlementReservationService;
        this.scanAuditor = scanAuditor;
    }

    @Transactional
    public AttendanceScanOutcome execute(
            AuthPrincipal principal,
            AttendanceScanRequest request,
            QrTokenPayload payload,
            LocalDateTime now) {
        QrTokenRow activeQr = requireActiveQr(principal, payload);
        AttendanceScanCandidateRow candidate =
                requireSingleCandidate(principal, payload.workplaceId(), now);

        AttendanceSuccessTimestampsRow attendance =
                attendanceRecordMapper.findSuccessTimestamps(candidate.getWorkCaseId());

        // 출근 직후 재시도를 퇴근으로 넘기지 않습니다. 거리·상태 검증보다 먼저 판정해 재시도가
        // 새 거절 기록을 만들지 않게 합니다.
        if (AttendanceWindowPolicy.isCheckInReplay(attendance.getCheckedInAt(), now)) {
            return AttendanceScanOutcome.recorded(
                    candidate.getWorkCaseId(),
                    AttendanceType.CHECK_IN,
                    attendance.getCheckedInAt(),
                    null);
        }

        if (attendance.getCheckedInAt() != null && attendance.getCheckedOutAt() != null) {
            return reject(
                    principal, candidate, activeQr, AttendanceType.CHECK_OUT,
                    AttendanceFailureReason.ALREADY_COMPLETED, null, now,
                    "이미 출퇴근이 모두 기록된 근무입니다.");
        }
        AttendanceType type = attendance.getCheckedInAt() == null
                ? AttendanceType.CHECK_IN
                : AttendanceType.CHECK_OUT;

        if (candidate.getWorkplaceLatitude() == null || candidate.getWorkplaceLongitude() == null) {
            return reject(
                    principal, candidate, activeQr, type,
                    AttendanceFailureReason.WORKPLACE_LOCATION_MISSING, null, now,
                    "사업장 위치가 등록되지 않아 처리할 수 없습니다.");
        }
        BigDecimal distance = distanceMeters(candidate, request);
        // 정확히 경계값이면 통과시킵니다. 반경은 허용 범위의 상한입니다.
        if (distance.compareTo(candidate.getAllowedRadiusMeters()) > 0) {
            return reject(
                    principal, candidate, activeQr, type,
                    AttendanceFailureReason.DISTANCE_EXCEEDED, distance, now,
                    "사업장에서 너무 멀리 떨어져 있습니다.");
        }

        return type == AttendanceType.CHECK_IN
                ? checkIn(principal, candidate, activeQr, distance, now)
                : checkOut(principal, candidate, activeQr, request, distance, now);
    }

    private AttendanceScanOutcome checkIn(
            AuthPrincipal principal,
            AttendanceScanCandidateRow candidate,
            QrTokenRow activeQr,
            BigDecimal distance,
            LocalDateTime now) {
        WorkLifecycleSnapshot lock = workLifecycleCommandService.lock(candidate.getWorkCaseId());
        if (lock == null || lock.status() != WorkCaseStatus.READY) {
            return reject(
                    principal, candidate, activeQr, AttendanceType.CHECK_IN,
                    AttendanceFailureReason.WORK_STATE_NOT_SCANNABLE, distance, now,
                    "현재 출근할 수 있는 근무가 아닙니다.");
        }

        insertSuccess(
                principal, candidate, activeQr, AttendanceType.CHECK_IN, distance, now, null);
        requireTransitioned(workLifecycleCommandService.transition(
                lock.workCaseId(), WorkCaseStatus.READY, WorkCaseStatus.IN_PROGRESS));

        return AttendanceScanOutcome.recorded(
                candidate.getWorkCaseId(), AttendanceType.CHECK_IN, now, null);
    }

    private AttendanceScanOutcome checkOut(
            AuthPrincipal principal,
            AttendanceScanCandidateRow candidate,
            QrTokenRow activeQr,
            AttendanceScanRequest request,
            BigDecimal distance,
            LocalDateTime now) {
        boolean early = now.isBefore(candidate.getEndsAt());
        if (early && !Boolean.TRUE.equals(request.getConfirmEarlyCheckout())) {
            // 확인 전에는 성공 행도 COMPLETED도 만들지 않습니다. 거절이 아니라 되물음이므로
            // 감사 행도 남기지 않습니다.
            return AttendanceScanOutcome.confirmationRequired(
                    candidate.getWorkCaseId(), candidate.getEndsAt());
        }

        WorkLifecycleSnapshot lock = workLifecycleCommandService.lock(candidate.getWorkCaseId());
        if (lock == null || lock.status() != WorkCaseStatus.IN_PROGRESS) {
            return reject(
                    principal, candidate, activeQr, AttendanceType.CHECK_OUT,
                    AttendanceFailureReason.WORK_STATE_NOT_SCANNABLE, distance, now,
                    "현재 퇴근할 수 있는 근무가 아닙니다.");
        }

        LocalDateTime earlyConfirmedAt = early ? now : null;
        insertSuccess(
                principal, candidate, activeQr, AttendanceType.CHECK_OUT, distance, now,
                earlyConfirmedAt);
        requireTransitioned(workLifecycleCommandService.transition(
                lock.workCaseId(), WorkCaseStatus.IN_PROGRESS, WorkCaseStatus.COMPLETED));
        // 지급 예정 시각만 예약합니다. Wallet·Escrow 금액과 원장은 바뀌지 않습니다.
        settlementReservationService.scheduleDueAt(lock.workCaseId(), now);

        return AttendanceScanOutcome.recorded(
                candidate.getWorkCaseId(), AttendanceType.CHECK_OUT, now, earlyConfirmedAt);
    }

    /**
     * 거절을 감사 기록으로 남기고 결과로 돌려줍니다.
     *
     * <p>{@code work_cases}를 잠근 뒤의 거절도 같은 Transaction에서 기록합니다. 잠금을 이미
     * 쥐고 있으므로 자기 자신과 FK 잠금을 두고 경쟁하지 않습니다.</p>
     */
    private AttendanceScanOutcome reject(
            AuthPrincipal principal,
            AttendanceScanCandidateRow candidate,
            QrTokenRow activeQr,
            AttendanceType type,
            AttendanceFailureReason reason,
            BigDecimal distance,
            LocalDateTime now,
            String message) {
        scanAuditor.recordRejection(
                candidate.getWorkCaseId(),
                principal.getUserId(),
                activeQr.getId(),
                type,
                reason,
                distance,
                now);
        return AttendanceScanOutcome.rejected(candidate.getWorkCaseId(), reason, message);
    }

    /**
     * 조건부 전이는 잠금 안에서 상태를 이미 확인했으므로 실패할 수 없습니다.
     *
     * <p>그래도 0행이면 잠금 밖에서 상태를 바꾼 경로가 생겼다는 뜻입니다. 이때는 성공 근태
     * 행과 상태가 어긋난 채 남지 않도록 Transaction 전체를 되돌립니다.</p>
     */
    private void requireTransitioned(boolean transitioned) {
        if (!transitioned) {
            throw new IllegalStateException("근무 상태 전이가 예상과 다르게 실패했습니다.");
        }
    }

    private void insertSuccess(
            AuthPrincipal principal,
            AttendanceScanCandidateRow candidate,
            QrTokenRow activeQr,
            AttendanceType type,
            BigDecimal distance,
            LocalDateTime now,
            LocalDateTime earlyCheckoutConfirmedAt) {
        AttendanceRecordInsertParam param = AttendanceRecordInsertParam.builder()
                .workCaseId(candidate.getWorkCaseId())
                .workerId(principal.getUserId())
                .qrTokenId(activeQr.getId())
                .attendanceType(type)
                .capturedAt(now)
                .attemptedAt(now)
                .distanceMeters(distance)
                .result(AttendanceResult.SUCCESS)
                .earlyCheckoutConfirmedAt(earlyCheckoutConfirmedAt)
                .build();
        try {
            if (attendanceRecordMapper.insertAttempt(param) != 1) {
                throw new IllegalStateException("근태 기록을 저장하지 못했습니다.");
            }
        } catch (DuplicateKeyException exception) {
            // 행 잠금을 잡았더라도 최종 보장은 uk_attendance_records_success입니다.
            throw new ConflictException("이미 기록된 근태입니다.");
        }
    }

    private QrTokenRow requireActiveQr(AuthPrincipal principal, QrTokenPayload payload) {
        QrTokenRow activeQr = qrTokenMapper.findActiveByWorkplaceId(payload.workplaceId());
        if (activeQr == null
                || !MessageDigest.isEqual(activeQr.getTokenNonce(), payload.nonce())) {
            scanAuditor.logUnresolvedRejection(principal.getUserId(), "QR_TOKEN_REVOKED");
            throw new ConflictException("사용할 수 없는 QR입니다. 사업장에 문의해 주세요.");
        }
        return activeQr;
    }

    /**
     * 지금 처리할 근무가 정확히 한 건인지 확인합니다.
     *
     * <p>0건과 복수를 같은 메시지로 합칩니다. 구분해 알려주면 다른 사람의 근무 존재 여부를
     * 추론할 수 있습니다.</p>
     */
    private AttendanceScanCandidateRow requireSingleCandidate(
            AuthPrincipal principal, long workplaceId, LocalDateTime now) {
        List<AttendanceScanCandidateRow> candidates = attendanceRecordMapper.findScanCandidates(
                principal.getUserId(),
                workplaceId,
                AttendanceWindowPolicy.latestScannableStartsAt(now),
                AttendanceWindowPolicy.earliestScannableEndsAt(now));
        if (candidates.size() != 1) {
            scanAuditor.logUnresolvedRejection(
                    principal.getUserId(),
                    candidates.isEmpty() ? "WORK_CASE_NOT_FOUND" : "WORK_CASE_AMBIGUOUS");
            throw new ConflictException("지금 출퇴근할 근무를 찾을 수 없습니다.");
        }
        return candidates.get(0);
    }

    private BigDecimal distanceMeters(
            AttendanceScanCandidateRow candidate, AttendanceScanRequest request) {
        return BigDecimal.valueOf(HaversineDistanceCalculator.distanceMeters(
                        candidate.getWorkplaceLatitude().doubleValue(),
                        candidate.getWorkplaceLongitude().doubleValue(),
                        request.getLatitude(),
                        request.getLongitude()))
                .setScale(DISTANCE_SCALE, RoundingMode.HALF_UP);
    }
}
