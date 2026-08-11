package com.gighub.attendance.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.gighub.attendance.domain.AttendanceFailureReason;
import com.gighub.attendance.domain.AttendanceResult;
import com.gighub.attendance.domain.AttendanceType;
import com.gighub.attendance.mapper.AttendanceRecordMapper;
import com.gighub.attendance.mapper.param.AttendanceRecordInsertParam;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 거절된 스캔 시도를 승인된 감사 규칙대로 남깁니다.
 *
 * <p>{@code DEC-ATTENDANCE-LOCATION-AUDIT}은 기록 대상을 둘로 나눕니다. 정확히 하나의 근무와
 * 출퇴근 유형을 정한 뒤의 의미상 거부만 {@code REJECTED} 근태 행으로 남기고, QR 변조와 후보
 * 없음·복수는 민감값 없는 보안 로그와 {@code traceId}만 남깁니다. 후자는 신뢰할 근무를 정할
 * 수 없어 {@code work_case_id}가 NOT NULL인 근태 행을 만들 수 없기도 합니다.</p>
 *
 * <p>거절 기록은 스캔과 <b>같은</b> Transaction에 담고, 호출부가 예외를 던지는 대신 거절
 * 결과를 돌려주어 commit 시킵니다. {@code REQUIRES_NEW}로 분리하면 바깥 Transaction이
 * {@code work_cases} 행에 배타 잠금을 쥔 채 새 Transaction이 같은 행의 FK 공유 잠금을
 * 기다리게 되어 스스로를 막습니다.</p>
 *
 * <p>WORKER 원문 좌표는 어느 쪽에도 남기지 않습니다. 근태 행에는 거리·정확도와 측정·시도
 * 시각만 남습니다.</p>
 */
@Service
public class AttendanceScanAuditor {

    private static final Logger log = LoggerFactory.getLogger(AttendanceScanAuditor.class);

    private final AttendanceRecordMapper attendanceRecordMapper;

    public AttendanceScanAuditor(AttendanceRecordMapper attendanceRecordMapper) {
        this.attendanceRecordMapper = attendanceRecordMapper;
    }

    /** 근무와 유형을 정한 뒤 거절된 시도를 진행 중인 Transaction에 기록합니다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordRejection(
            long workCaseId,
            long workerId,
            Long qrTokenId,
            AttendanceType attendanceType,
            AttendanceFailureReason reason,
            BigDecimal distanceMeters,
            BigDecimal accuracyMeters,
            LocalDateTime capturedAt,
            LocalDateTime attemptedAt) {
        attendanceRecordMapper.insertAttempt(AttendanceRecordInsertParam.builder()
                .workCaseId(workCaseId)
                .workerId(workerId)
                .qrTokenId(qrTokenId)
                .attendanceType(attendanceType)
                .capturedAt(capturedAt)
                .attemptedAt(attemptedAt)
                .distanceMeters(distanceMeters)
                .accuracyMeters(accuracyMeters)
                .result(AttendanceResult.REJECTED)
                .failureReason(reason.name())
                .build());
    }

    /**
     * 신뢰할 근무를 정할 수 없어 근태 행을 만들 수 없는 거절을 남깁니다.
     *
     * <p>좌표와 Token 원문은 남기지 않습니다. 정밀 위치와 서명 값이 일반 로그로 새어 나가면
     * 안 됩니다. 응답의 {@code traceId}가 이 로그와 요청을 이어 줍니다.</p>
     */
    public void logUnresolvedRejection(long workerId, String reason) {
        log.warn("근태 스캔을 근무 특정 전에 거절했습니다. workerId={}, reason={}", workerId, reason);
    }
}
