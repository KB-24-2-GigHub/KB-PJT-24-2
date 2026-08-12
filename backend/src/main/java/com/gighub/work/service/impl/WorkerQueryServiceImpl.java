package com.gighub.work.service.impl;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.PageRequests;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.member.domain.UserRole;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.domain.AttendanceStateIntegrity;
import com.gighub.work.domain.AttendanceStateViolation;
import com.gighub.work.dto.WorkerHomeResponse;
import com.gighub.work.dto.WorkerWorkCaseListItemResponse;
import com.gighub.work.mapper.WorkerMapper;
import com.gighub.work.mapper.param.WorkerWorkCaseListQuery;
import com.gighub.work.mapper.result.WorkerHomeCandidateRow;
import com.gighub.work.mapper.result.WorkerWorkCaseRow;
import com.gighub.work.service.WorkerQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** WORKER 홈·근무 이력 조회를 담당합니다. DML은 이 Service가 소유하지 않습니다. */
@Service
public class WorkerQueryServiceImpl implements WorkerQueryService {

    private static final Logger log = LoggerFactory.getLogger(WorkerQueryServiceImpl.class);

    /** {@code work_cases.starts_at}은 Asia/Seoul 벽시계 값이라 오늘 판정도 같은 시간대를 쓴다. */
    private static final ZoneId WORK_CASE_ZONE = ZoneId.of("Asia/Seoul");

    private final WorkerMapper workerMapper;

    public WorkerQueryServiceImpl(WorkerMapper workerMapper) {
        this.workerMapper = workerMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public WorkerHomeResponse home(AuthPrincipal principal) {
        requireWorker(principal);

        LocalDate today = LocalDate.now(WORK_CASE_ZONE);
        WorkerHomeCandidateRow row = workerMapper.findTodayCandidate(
                principal.getUserId(),
                today.minusDays(1).atStartOfDay(),
                today.atStartOfDay(),
                today.plusDays(1).atStartOfDay());

        if (row != null) {
            reportIntegrity(
                    row.getWorkCaseId(), row.getStatus(),
                    row.getCheckedInAt(), row.getCheckedOutAt());
        }
        return row == null ? WorkerHomeResponse.empty() : toHomeResponse(row);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<WorkerWorkCaseListItemResponse> workCases(AuthPrincipal principal, int page, int size) {
        requireWorker(principal);
        PageRequests.validate(page, size);

        WorkerWorkCaseListQuery query = WorkerWorkCaseListQuery.builder()
                .workerId(principal.getUserId())
                .size(size)
                .offset(PageRequests.offset(page, size))
                .build();

        long totalElements = workerMapper.countByWorker(query);
        List<WorkerWorkCaseRow> rows = workerMapper.findPage(query);
        rows.forEach(row -> reportIntegrity(
                row.getWorkCaseId(), row.getStatus(),
                row.getCheckedInAt(), row.getCheckedOutAt()));

        List<WorkerWorkCaseListItemResponse> content = rows.stream()
                .map(this::toWorkCaseResponse)
                .toList();

        return PageResponse.of(content, page, size, totalElements);
    }

    /** 조회 Row의 DB 시각과 nullable snapshot을 공개 응답으로 옮기는 경계를 한곳에 둡니다. */
    private WorkerHomeResponse toHomeResponse(WorkerHomeCandidateRow row) {
        return WorkerHomeResponse.of(
                row.getWorkCaseId(),
                row.getTitle(),
                row.getWorkplaceName(),
                row.getStartsAt(),
                row.getEndsAt(),
                row.getBreakMinutes(),
                row.getBreakPaid(),
                row.getDailyWage(),
                row.getStatus(),
                row.getCheckedInAt(),
                row.getCheckInAttemptedAt(),
                row.getCheckedOutAt(),
                row.getEscrowStatus(),
                row.getSettlementStatus(),
                row.getSettlementDueAt());
    }

    private WorkerWorkCaseListItemResponse toWorkCaseResponse(WorkerWorkCaseRow row) {
        return WorkerWorkCaseListItemResponse.of(
                row.getWorkCaseId(),
                row.getTitle(),
                row.getWorkplaceName(),
                row.getStartsAt(),
                row.getEndsAt(),
                row.getBreakMinutes(),
                row.getBreakPaid(),
                row.getDailyWage(),
                row.getStatus(),
                row.getCheckedInAt(),
                row.getCheckInAttemptedAt(),
                row.getCheckedOutAt(),
                row.getEscrowStatus(),
                row.getSettlementStatus(),
                row.getSettlementDueAt());
    }

    /**
     * 상태와 성공 근태가 어긋나면 서버 로그에 남깁니다.
     *
     * <p>응답은 저장된 값을 그대로 내보냅니다. 조회를 실패로 바꾸면 이미 저장된 모순 하나
     * 때문에 WORKER가 본인 근무를 전혀 볼 수 없게 되므로, 화면은 유지하고 어긋남만 운영이
     * 볼 수 있는 신호로 남깁니다. 조용히 지나가면 잘못된 데이터가 정상처럼 보입니다.</p>
     */
    private void reportIntegrity(
            Long workCaseId,
            WorkCaseStatus status,
            LocalDateTime checkedInAt,
            LocalDateTime checkedOutAt) {
        List<AttendanceStateViolation> violations =
                AttendanceStateIntegrity.verify(status, checkedInAt, checkedOutAt);
        if (!violations.isEmpty()) {
            log.warn("근무 Case {}의 상태 {}와 성공 근태가 어긋납니다: {} (checkedInAt={}, checkedOutAt={})",
                    workCaseId, status, violations, checkedInAt, checkedOutAt);
        }
    }

    /**
     * Security 설정은 인증 여부만 강제하므로 역할 경계는 도메인에서 확인합니다.
     *
     * <p>거절 근거가 역할 하나뿐이라 {@code 403 ROLE_MISMATCH}로 응답합니다.</p>
     */
    private void requireWorker(AuthPrincipal principal) {
        if (principal.getRole() != UserRole.WORKER) {
            throw new RoleMismatchException("WORKER 홈과 근무 이력은 WORKER만 조회할 수 있습니다.");
        }
    }
}
