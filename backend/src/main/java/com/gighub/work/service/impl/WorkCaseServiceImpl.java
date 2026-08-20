package com.gighub.work.service.impl;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.PageRequests;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.ValidationException;
import com.gighub.common.exception.WorkCaseLockedException;
import com.gighub.member.domain.UserRole;
import com.gighub.work.domain.WorkCaseAddress;
import com.gighub.work.domain.WorkCaseDecision;
import com.gighub.work.domain.WorkCasePolicy;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.domain.WorkCaseTimes;
import com.gighub.work.dto.WorkCaseDetailResponse;
import com.gighub.work.dto.WorkCaseListItemResponse;
import com.gighub.work.dto.WorkCaseSummaryResponse;
import com.gighub.work.mapper.WorkCaseMapper;
import com.gighub.work.mapper.param.WorkCaseInsertParam;
import com.gighub.work.mapper.param.WorkCaseListQuery;
import com.gighub.work.mapper.param.WorkCaseTermsUpdateParam;
import com.gighub.work.mapper.result.AttendanceSummaryRow;
import com.gighub.work.mapper.result.ContractDetailRow;
import com.gighub.work.mapper.result.EscrowSummaryRow;
import com.gighub.work.mapper.result.LatestInvitationRow;
import com.gighub.work.mapper.result.OwnedWorkplaceSnapshotRow;
import com.gighub.work.mapper.result.SettlementSummaryRow;
import com.gighub.work.mapper.result.WorkCaseDetailRow;
import com.gighub.work.mapper.result.WorkCaseListRow;
import com.gighub.work.mapper.result.WorkCaseLockRow;
import com.gighub.work.service.WorkCaseService;
import com.gighub.work.service.command.WorkCaseCreateCommand;
import com.gighub.work.service.command.WorkCaseUpdateCommand;
import com.gighub.invitation.mapper.InvitationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 승인된 근무 {@code DRAFT} 계약을 인증 Principal과 DB 현재 상태로 적용합니다. */
@Service
@RequiredArgsConstructor
public class WorkCaseServiceImpl implements WorkCaseService {

    private final WorkCaseMapper workCaseMapper;
    private final InvitationMapper invitationMapper;

    @Override
    @Transactional
    public Long create(AuthPrincipal principal, WorkCaseCreateCommand command) {
        requireOwner(principal);

        LocalDateTime startsAt = WorkCaseTimes.combine(command.getWorkDate(), command.getStartTime());
        LocalDateTime endsAt = WorkCaseTimes.combineEnd(
                command.getWorkDate(), command.getStartTime(), command.getEndTime());
        requireValidWorkPeriod(
                startsAt, endsAt, command.getBreakMinutes(), command.getBreakPaid());

        // 소유권·ACTIVE 확인과 Snapshot 원본 조회를 한 쿼리로 처리합니다. 없으면 사업장이
        // 없거나 다른 OWNER 소유이거나 INACTIVE인 것이며, 세 경우를 구분해 노출하지 않습니다.
        OwnedWorkplaceSnapshotRow snapshot = workCaseMapper.findOwnedActiveWorkplace(
                command.getWorkplaceId(), principal.getUserId());
        if (snapshot == null) {
            throw new ResourceNotFoundException("등록할 수 있는 사업장을 찾을 수 없습니다.");
        }

        WorkCaseInsertParam param = WorkCaseInsertParam.builder()
                .employerId(principal.getUserId())
                .workplaceId(command.getWorkplaceId())
                .title(command.getTitle())
                .startsAt(startsAt)
                .endsAt(endsAt)
                .breakMinutes(command.getBreakMinutes())
                .breakPaid(command.getBreakPaid())
                .workplaceName(snapshot.getWorkplaceName())
                .workplaceAddress(WorkCaseAddress.combine(
                        snapshot.getRoadAddress(), snapshot.getDetailAddress()))
                .workplaceLatitude(snapshot.getLatitude())
                .workplaceLongitude(snapshot.getLongitude())
                .allowedRadiusMeters(snapshot.getRadiusMeters())
                .dailyWage(command.getDailyWage())
                .build();

        workCaseMapper.insert(param);
        // 생성 Key 회수가 깨지면 201과 함께 workCaseId=null이 조용히 나갑니다.
        // ApiResponse는 래퍼만 검사하므로 여기서 끊습니다.
        return Objects.requireNonNull(param.getWorkCaseId(), "생성된 근무 Case 식별자");
    }

    @Override
    @Transactional
    public void update(AuthPrincipal principal, WorkCaseUpdateCommand command) {
        requireOwner(principal);
        WorkCaseLockRow lock = lockOwned(principal, command.getWorkCaseId());
        requireDraft(lock);

        LocalDateTime startsAt = WorkCaseTimes.combine(command.getWorkDate(), command.getStartTime());
        LocalDateTime endsAt = WorkCaseTimes.combineEnd(
                command.getWorkDate(), command.getStartTime(), command.getEndTime());
        requireValidWorkPeriod(
                startsAt, endsAt, command.getBreakMinutes(), command.getBreakPaid());

        WorkCaseTermsUpdateParam param = WorkCaseTermsUpdateParam.builder()
                .workCaseId(command.getWorkCaseId())
                .title(command.getTitle())
                .startsAt(startsAt)
                .endsAt(endsAt)
                .breakMinutes(command.getBreakMinutes())
                .breakPaid(command.getBreakPaid())
                .dailyWage(command.getDailyWage())
                .build();

        // 행을 이미 잠그고 DRAFT임을 확인했으므로 이 UPDATE는 반드시 1행을 바꿉니다. 0이면
        // 잠금과 갱신 사이의 가정이 깨진 것이라 방어적으로 다루지 않고 그대로 드러냅니다.
        if (workCaseMapper.updateDraftTerms(param) != 1) {
            throw new IllegalStateException("잠근 DRAFT 근무 조건을 갱신하지 못했습니다.");
        }
        // 조건이 바뀌면 이전 조건으로 발급된 PENDING 초대는 더 이상 유효하지 않습니다.
        // 활성 PENDING은 근무당 하나뿐이라 Version별 조건 없이 그대로 철회합니다.
        invitationMapper.revokePendingByWorkCaseIdNow(command.getWorkCaseId());
    }

    @Override
    @Transactional
    public void delete(AuthPrincipal principal, Long workCaseId) {
        requireOwner(principal);
        WorkCaseLockRow lock = lockOwned(principal, workCaseId);
        requireDraft(lock);

        // DRAFT는 ACCEPTED 이후에만 만들어지는 계약·에스크로를 가질 수 없습니다. 상태 확인이
        // 곧 "계약·에스크로가 없음"의 증명이라 별도 존재 조회를 추가하지 않습니다.
        if (workCaseMapper.countInvitations(workCaseId) == 0) {
            deleteOrReportLocked(workCaseId);
            return;
        }

        invitationMapper.revokePendingByWorkCaseIdNow(workCaseId);
        // CANCELED 전이는 status 등 일부 컬럼만 바꾸는 UPDATE라 자식 테이블의 FK RESTRICT를
        // 건드리지 않습니다. 행 자체를 지우는 DELETE만 참조 무결성 위반 가능성이 있습니다.
        if (workCaseMapper.cancelDraft(workCaseId) != 1) {
            throw new IllegalStateException("잠근 DRAFT 근무를 취소하지 못했습니다.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public WorkCaseSummaryResponse summary(AuthPrincipal principal, Long workplaceId) {
        requireOwner(principal);
        // 소유하지 않은 사업장과 근무 0건인 소유 사업장을 구분해야 하므로, 집계 전에 소유권을
        // 먼저 확인합니다. countByStatus만으로는 두 경우가 똑같이 빈 결과로 보입니다.
        if (!workCaseMapper.existsOwnedManageableWorkplace(workplaceId, principal.getUserId())) {
            throw new ResourceNotFoundException("사업장을 찾을 수 없습니다.");
        }

        Map<WorkCaseStatus, Long> counts = new EnumMap<>(WorkCaseStatus.class);
        workCaseMapper.countByStatus(workplaceId, principal.getUserId())
                .forEach(row -> counts.put(row.getStatus(), row.getCaseCount()));
        return WorkCaseSummaryResponse.of(counts);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<WorkCaseListItemResponse> list(
            AuthPrincipal principal,
            Long workplaceId,
            String keyword,
            WorkCaseStatus status,
            LocalDate from,
            LocalDate to,
            int page,
            int size) {
        requireOwner(principal);
        // 역할을 먼저 확인합니다. Page 값이 잘못된 요청이라도 권한 없는 호출자에게 400을
        // 돌려주면 Endpoint의 존재와 Query 규칙을 알려주게 됩니다.
        PageRequests.validate(page, size);
        if (!workCaseMapper.existsOwnedManageableWorkplace(workplaceId, principal.getUserId())) {
            throw new ResourceNotFoundException("사업장을 찾을 수 없습니다.");
        }
        requireValidDateRange(from, to);

        WorkCaseListQuery query = WorkCaseListQuery.builder()
                .workplaceId(workplaceId)
                .ownerUserId(principal.getUserId())
                .keyword(normalizeKeyword(keyword))
                .status(status)
                .from(from)
                .to(to)
                .size(size)
                .offset(PageRequests.offset(page, size))
                .build();

        long totalElements = workCaseMapper.countByFilters(query);
        List<WorkCaseListItemResponse> content = workCaseMapper.findPageByFilters(query).stream()
                .map(this::toListItemResponse)
                .toList();

        return PageResponse.of(content, page, size, totalElements);
    }

    @Override
    @Transactional(readOnly = true)
    public WorkCaseDetailResponse detail(AuthPrincipal principal, Long workCaseId) {
        WorkCaseDetailRow row = workCaseMapper.findDetailRow(workCaseId);
        if (row == null) {
            throw new ResourceNotFoundException("근무 Case를 찾을 수 없습니다.");
        }
        requireParty(principal, row);

        return toDetailResponse(
                row,
                workCaseMapper.findLatestInvitation(workCaseId),
                requireContractIntegrity(workCaseId),
                workCaseMapper.findAttendanceTimestamps(workCaseId),
                workCaseMapper.findEscrow(workCaseId),
                workCaseMapper.findSettlement(workCaseId));
    }

    /** SQL Row를 API 응답으로 바꾸는 책임을 persistence DTO 밖의 Application 경계에 둡니다. */
    private WorkCaseListItemResponse toListItemResponse(WorkCaseListRow row) {
        return WorkCaseListItemResponse.of(
                row.getWorkCaseId(),
                row.getTitle(),
                row.getStartsAt(),
                row.getEndsAt(),
                row.getDailyWage(),
                row.getStatus(),
                row.getWorkerId(),
                row.getWorkerName());
    }

    private WorkCaseDetailResponse toDetailResponse(
            WorkCaseDetailRow row,
            LatestInvitationRow invitation,
            ContractDetailRow contract,
            AttendanceSummaryRow attendance,
            EscrowSummaryRow escrow,
            SettlementSummaryRow settlement) {
        return WorkCaseDetailResponse.of(
                row.getWorkCaseId(),
                row.getTitle(),
                row.getStartsAt(),
                row.getEndsAt(),
                row.getBreakMinutes(),
                row.getBreakPaid(),
                row.getDailyWage(),
                row.getStatus(),
                row.getTermsVersion(),
                row.getWorkplaceName(),
                row.getWorkplaceAddress(),
                row.getWorkerId() == null
                        ? null
                        : WorkCaseDetailResponse.WorkerSummary.of(
                                row.getWorkerId(), row.getWorkerName()),
                invitation == null
                        ? null
                        : WorkCaseDetailResponse.InvitationSummary.of(
                                invitation.getStatus(),
                                invitation.getTermsVersion(),
                                invitation.getExpiresAt()),
                contract == null
                        ? null
                        : WorkCaseDetailResponse.ContractSummary.of(
                                contract.getContractId(),
                                visibleDocumentId(contract),
                                contract.getSourceTermsVersion(),
                                contract.getAcceptedAt()),
                WorkCaseDetailResponse.AttendanceSummary.of(
                        attendance == null ? null : attendance.getCheckedInAt(),
                        attendance == null ? null : attendance.getCheckedOutAt()),
                escrow == null
                        ? null
                        : WorkCaseDetailResponse.EscrowSummary.of(
                                escrow.getStatus(), escrow.getAmount()),
                settlement == null
                        ? null
                        : WorkCaseDetailResponse.SettlementSummary.of(
                                settlement.getStatus(),
                                settlement.getAmount(),
                                settlement.getWorkerPaidAmount(),
                                settlement.getOwnerRefundAmount(),
                                settlement.getDeductionBaseMinutes(),
                                settlement.getLateMinutes(),
                                settlement.getEarlyLeaveMinutes(),
                                settlement.getCalculationReason(),
                                settlement.getCalculationVersion(),
                                settlement.getCalculatedAt(),
                                settlement.getDueAt(),
                                settlement.getCompletedAt()));
    }

    /**
     * 해당 근무의 OWNER 또는 매칭 WORKER만 통과시킵니다.
     *
     * <p>미매칭 {@code DRAFT}는 {@code workerId}가 없어 OWNER만 당사자로 남습니다. 존재하지
     * 않는 근무와 당사자가 아닌 접근을 같은 404로 응답해 "존재는 하지만 내 것이 아니다"라는
     * 사실을 노출하지 않습니다.</p>
     */
    private void requireParty(AuthPrincipal principal, WorkCaseDetailRow row) {
        boolean isEmployer = row.getEmployerId().equals(principal.getUserId());
        boolean isMatchedWorker = row.getWorkerId() != null
                && row.getWorkerId().equals(principal.getUserId());
        if (!isEmployer && !isMatchedWorker) {
            throw new ResourceNotFoundException("근무 Case를 찾을 수 없습니다.");
        }
    }

    private static final String DOCUMENT_STATUS_DELETED = "DELETED";

    /**
     * 계약은 있는데 연결 문서 행 자체가 없는 손상 상태를 API_SPEC 4.0.0 계약대로 500으로
     * 드러냅니다.
     *
     * <p>부분 객체나 {@code null}로 감추면 클라이언트가 {@code contract.documentId}로 계약
     * 파일을 정상 조회할 수 있다고 착각합니다. 예외 메시지에 식별자를 담아 공통
     * {@code Exception} Handler가 traceId와 함께 서버 로그에 남기게 합니다.</p>
     *
     * <p>문서 행이 있지만 {@code DELETED}(보존 만료 파기, DOC-012)인 경우는 손상이 아니라
     * 정상 정책 결과이므로 여기서는 통과시키고, {@link #visibleDocumentId(ContractDetailRow)}가
     * 응답에서 그 documentId를 감춘다.</p>
     */
    private ContractDetailRow requireContractIntegrity(Long workCaseId) {
        ContractDetailRow contract = workCaseMapper.findContractDetail(workCaseId);
        if (contract != null && contract.getDocumentId() == null) {
            throw new IllegalStateException(
                    "근무 Case " + workCaseId + "의 계약 " + contract.getContractId()
                            + "에 연결된 계약서 문서가 없습니다.");
        }
        return contract;
    }

    /**
     * 파기된(DELETED) 계약서 문서는 이미 조회할 수 없으므로 documentId를 감춰 클라이언트가
     * 파기된 문서를 정상 조회 가능한 것으로 착각해 죽은 링크를 호출하지 않게 한다.
     */
    private static Long visibleDocumentId(ContractDetailRow contract) {
        return DOCUMENT_STATUS_DELETED.equals(contract.getDocumentStatus())
                ? null
                : contract.getDocumentId();
    }

    /**
     * 앞뒤 공백만 제거합니다. 공백만 남는 검색어는 {@code null}로 바꿔 미지정과 같게 취급합니다.
     */
    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void requireValidDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new ValidationException("from은 to보다 늦을 수 없습니다.");
        }
    }

    /**
     * DRAFT는 계약·에스크로를 가질 수 없어 FK RESTRICT를 정상적으로 만나지 않지만, 그 불변식이
     * 나중에 깨지더라도 원시 SQL 예외가 그대로 노출되지 않도록 승인 오류로 변환합니다.
     */
    private void deleteOrReportLocked(Long workCaseId) {
        try {
            if (workCaseMapper.deleteDraft(workCaseId) != 1) {
                throw new IllegalStateException("잠근 DRAFT 근무를 삭제하지 못했습니다.");
            }
        } catch (DataIntegrityViolationException referenced) {
            throw new WorkCaseLockedException("참조 중인 근무는 삭제할 수 없습니다.");
        }
    }

    /**
     * Security 설정은 인증 여부만 강제하므로 역할 경계는 도메인에서 확인합니다.
     *
     * <p>거절 근거가 역할 하나뿐이라 {@code 403 ROLE_MISMATCH}로 응답합니다.</p>
     */
    private void requireOwner(AuthPrincipal principal) {
        if (principal.getRole() != UserRole.OWNER) {
            throw new RoleMismatchException("근무 Case는 OWNER만 관리할 수 있습니다.");
        }
    }

    /**
     * 근무 행을 잠그고 호출자가 소유자인지 확인합니다.
     *
     * <p>존재하지 않는 근무와 다른 OWNER의 근무를 같은 404로 응답합니다. 403으로 구분하면
     * "존재는 하지만 내 것이 아니다"라는 사실이 노출됩니다.</p>
     */
    private WorkCaseLockRow lockOwned(AuthPrincipal principal, Long workCaseId) {
        WorkCaseLockRow lock = workCaseMapper.lockById(workCaseId);
        if (lock == null || !lock.getEmployerId().equals(principal.getUserId())) {
            throw new ResourceNotFoundException("근무 Case를 찾을 수 없습니다.");
        }
        return lock;
    }

    private void requireDraft(WorkCaseLockRow lock) {
        WorkCaseDecision decision = WorkCasePolicy.decideDraftMutation(lock.getStatus());
        if (decision != WorkCaseDecision.ALLOWED) {
            throw new WorkCaseLockedException("DRAFT 상태의 근무만 처리할 수 있습니다.");
        }
    }

    /**
     * 결합된 근무 구간이 저장 가능한지 확인합니다(SPEC-413-01).
     *
     * <p>순서 조건은 {@link WorkCaseTimes#combineEnd} 결과에서는 구조적으로 참이지만,
     * {@code ck_work_cases_time}의 애플리케이션 쪽 짝이라 그대로 둡니다. 사용자가 실제로
     * 마주치는 거절은 길이 상한 쪽입니다 — 자정 넘김을 허용하면서 순서 검증이 잡아 주던
     * 오타를 이 상한이 대신 잡습니다.</p>
     *
     * <p>거절은 {@code fieldErrors}를 함께 실어 보냅니다. 지금은 화면이 같은 경계를 먼저
     * 걸러 주지만, 두 상한이 어긋나는 순간 사용자가 마주치는 것이 정확히 이 경로입니다.
     * 필드가 비어 있으면 화면은 어느 입력이 문제인지 알려주지 못하고 실패 Toast만 띄웁니다.</p>
     *
     * <p>휴게 시간도 여기서 함께 봅니다. {@code breakMinutes}의 Bean Validation 상한은
     * {@code SMALLINT UNSIGNED} 표현 범위(65535)뿐이라 근무 시간보다 긴 값이 통과합니다.
     * 그 값은 등록 시점에는 조용히 저장됐다가 초대 수락 트랜잭션에서
     * {@code ContractSnapshot}이 거절해 500이 됩니다 — 계약·에스크로가 함께 도는 자리라
     * 여기서 400으로 앞당깁니다.</p>
     */
    private void requireValidWorkPeriod(
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            Integer breakMinutes,
            Boolean breakPaid) {
        if (!WorkCaseTimes.endsAfterStart(startsAt, endsAt)) {
            throw new IllegalStateException("결합한 종료 시각이 시작 시각보다 뒤가 아닙니다.");
        }
        long maxHours = WorkCaseTimes.MAX_WORK_DURATION.toHours();
        if (!WorkCaseTimes.withinMaxDuration(startsAt, endsAt)) {
            throw new ValidationException(
                    String.format("근무 시간은 최대 %d시간까지 등록할 수 있습니다.", maxHours),
                    "endTime",
                    String.format("근무 시간은 최대 %d시간까지 등록할 수 있습니다.", maxHours));
        }

        long workMinutes = Duration.between(startsAt, endsAt).toMinutes();
        boolean invalidBreak = breakMinutes != null
                && (breakMinutes > workMinutes
                || (!Boolean.TRUE.equals(breakPaid) && breakMinutes >= workMinutes));
        if (invalidBreak) {
            String reason = Boolean.TRUE.equals(breakPaid)
                    ? String.format("휴게 시간은 근무 시간(%d분)을 넘을 수 없습니다.", workMinutes)
                    : String.format(
                            "무급 휴게 시간은 근무 시간(%d분)보다 짧아야 합니다.", workMinutes);
            throw new ValidationException(reason, "breakMinutes", reason);
        }
    }
}
