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
 * 거절된 스캔 시도를 감사 기록으로 남깁니다.
 *
 * <p>거절 기록은 스캔과 <b>같은</b> Transaction에 담고, 호출부가 예외를 던지는 대신 거절
 * 결과를 돌려주어 commit 시킵니다. {@code REQUIRES_NEW}로 분리하면 바깥 Transaction이
 * {@code work_cases} 행에 배타 잠금을 쥔 채로 새 Transaction이 같은 행의 FK 공유 잠금을
 * 기다리게 되어 스스로를 막습니다.</p>
 *
 * <p>{@code attendance_records.work_case_id}는 NOT NULL이고 {@code work_cases}를 참조합니다.
 * 따라서 근무를 특정하기 전에 끝난 거절(변조·폐기 QR, 후보 0건·복수)은 이 테이블에 담을 수
 * 없어 application log로만 남깁니다. 그 시도까지 DB 감사로 남기려면 근무에 묶이지 않는 별도
 * 감사 테이블이 필요하며, 이는 관리자 승인이 필요한 Schema 결정입니다.</p>
 */
@Service
public class AttendanceScanAuditor {

    private static final Logger log = LoggerFactory.getLogger(AttendanceScanAuditor.class);

    private final AttendanceRecordMapper attendanceRecordMapper;

    public AttendanceScanAuditor(AttendanceRecordMapper attendanceRecordMapper) {
        this.attendanceRecordMapper = attendanceRecordMapper;
    }

    /** 근무를 특정한 뒤 거절된 시도를 진행 중인 Transaction에 기록합니다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordRejection(
            long workCaseId,
            long workerId,
            Long qrTokenId,
            AttendanceType attendanceType,
            AttendanceFailureReason reason,
            BigDecimal distanceMeters,
            LocalDateTime attemptedAt) {
        attendanceRecordMapper.insertAttempt(AttendanceRecordInsertParam.builder()
                .workCaseId(workCaseId)
                .workerId(workerId)
                .qrTokenId(qrTokenId)
                .attendanceType(attendanceType)
                .capturedAt(attemptedAt)
                .attemptedAt(attemptedAt)
                .distanceMeters(distanceMeters)
                .result(AttendanceResult.REJECTED)
                .failureReason(reason.name())
                .build());
    }

    /**
     * 근무를 특정하기 전에 끝난 거절을 남깁니다.
     *
     * <p>좌표와 Token 원문은 남기지 않습니다. 정밀 위치와 서명 값이 일반 로그로 새어 나가면
     * 안 됩니다.</p>
     */
    public void logUnresolvedRejection(long workerId, String reason) {
        log.warn("근태 스캔을 근무 특정 전에 거절했습니다. workerId={}, reason={}", workerId, reason);
    }
}
