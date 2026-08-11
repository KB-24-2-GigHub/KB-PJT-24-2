package com.gighub.attendance.service.impl;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import com.gighub.attendance.domain.AttendanceScanOutcome;
import com.gighub.attendance.dto.AttendanceScanRequest;
import com.gighub.attendance.dto.AttendanceScanResponse;
import com.gighub.attendance.qr.QrTokenCodec;
import com.gighub.attendance.qr.QrTokenPayload;
import com.gighub.attendance.service.AttendanceScanAuditor;
import com.gighub.attendance.service.AttendanceScanExecutor;
import com.gighub.attendance.service.AttendanceScanService;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.ApiTimes;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.ValidationException;
import com.gighub.member.domain.UserRole;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 스캔 요청의 권한·Token을 확인하고 판정 결과를 API 응답으로 옮깁니다.
 *
 * <p>DB 판정은 {@link AttendanceScanExecutor}가 한 Transaction으로 처리합니다. 거절을 응답
 * 오류로 바꾸는 일은 그 Transaction이 commit 된 뒤에 해야 감사 기록이 남으므로, 변환을
 * Transaction 밖인 이 클래스가 맡습니다.</p>
 */
@Service
public class AttendanceScanServiceImpl implements AttendanceScanService {

    private static final ZoneId DATABASE_ZONE = ZoneId.of("Asia/Seoul");

    private final QrTokenCodec qrTokenCodec;
    private final AttendanceScanExecutor scanExecutor;
    private final AttendanceScanAuditor scanAuditor;
    private final Clock clock;

    @Autowired
    public AttendanceScanServiceImpl(
            QrTokenCodec qrTokenCodec,
            AttendanceScanExecutor scanExecutor,
            AttendanceScanAuditor scanAuditor) {
        this(qrTokenCodec, scanExecutor, scanAuditor, Clock.system(DATABASE_ZONE));
    }

    /** 경계 시각 테스트에서만 고정 Clock을 주입합니다. */
    AttendanceScanServiceImpl(
            QrTokenCodec qrTokenCodec,
            AttendanceScanExecutor scanExecutor,
            AttendanceScanAuditor scanAuditor,
            Clock clock) {
        this.qrTokenCodec = qrTokenCodec;
        this.scanExecutor = scanExecutor;
        this.scanAuditor = scanAuditor;
        this.clock = clock;
    }

    @Override
    public AttendanceScanResponse scan(AuthPrincipal principal, AttendanceScanRequest request) {
        requireWorkerRole(principal);
        QrTokenPayload payload = decodeToken(principal, request.getQrToken());

        AttendanceScanOutcome outcome = scanExecutor.execute(
                principal, request, payload, LocalDateTime.now(clock));

        if (outcome.isRejected()) {
            // 감사 행은 이미 commit 되었습니다. 여기서 던지는 예외는 그 기록을 되돌리지
            // 않습니다.
            throw new ConflictException(outcome.getFailureMessage());
        }
        if (outcome.isConfirmationRequired()) {
            return AttendanceScanResponse.confirmationRequired(
                    outcome.getWorkCaseId(), ApiTimes.toInstant(outcome.getScheduledEndAt()));
        }
        return AttendanceScanResponse.recorded(
                outcome.getWorkCaseId(),
                outcome.getScanType(),
                ApiTimes.toInstant(outcome.getRecordedAt()),
                ApiTimes.toInstant(outcome.getEarlyCheckoutConfirmedAt()));
    }

    /**
     * 구조·Version·HMAC을 한 번에 확인합니다.
     *
     * <p>실패 원인을 나누지 않습니다. 어느 단계까지 통과했는지 알려주면 Token을 맞춰 볼 수
     * 있게 됩니다.</p>
     */
    private QrTokenPayload decodeToken(AuthPrincipal principal, String qrToken) {
        return qrTokenCodec.verify(qrToken)
                .orElseGet(() -> {
                    scanAuditor.logUnresolvedRejection(principal.getUserId(), "QR_TOKEN_INVALID");
                    throw new ValidationException(
                            "QR 코드를 확인할 수 없습니다.", "qrToken", "INVALID");
                });
    }

    private void requireWorkerRole(AuthPrincipal principal) {
        if (principal.getRole() != UserRole.WORKER) {
            throw new RoleMismatchException("근태 스캔은 WORKER만 사용할 수 있습니다.");
        }
    }
}
