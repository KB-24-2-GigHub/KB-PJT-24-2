package com.gighub.settlement.service.impl;

import com.gighub.common.api.ApiTimes;
import com.gighub.common.api.PageRequests;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.common.exception.ValidationException;
import com.gighub.member.domain.UserRole;
import com.gighub.settlement.domain.SettlementStatus;
import com.gighub.settlement.dto.DisputeDemoReviewResponse;
import com.gighub.settlement.dto.DisputeListItemResponse;
import com.gighub.settlement.dto.SettlementSnapshot;
import com.gighub.settlement.exception.DisputeAlreadyOpenException;
import com.gighub.settlement.mapper.DisputeMapper;
import com.gighub.settlement.mapper.SettlementMapper;
import com.gighub.settlement.mapper.command.DisputeInsert;
import com.gighub.settlement.mapper.result.DisputeListRow;
import com.gighub.settlement.review.DisputeReviewExecutionStatus;
import com.gighub.settlement.review.DisputeReviewJsonCodec;
import com.gighub.settlement.review.DisputeReviewResult;
import com.gighub.settlement.review.DisputeReviewResults;
import com.gighub.settlement.service.DisputeService;
import com.gighub.settlement.service.DisputeReviewQueueService;
import com.gighub.settlement.service.command.DisputeCreateCommand;
import com.gighub.settlement.service.command.DisputeReviewEnqueueCommand;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.service.WorkSettlementService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;

/** 분쟁 생성·조회와 정산 보류의 Transaction 경계를 소유합니다. */
@Service
@RequiredArgsConstructor
public class DisputeServiceImpl implements DisputeService {

    private static final Logger log = LoggerFactory.getLogger(DisputeServiceImpl.class);
    private static final int TITLE_MAX_LENGTH = 100;
    private static final int CONTENT_MAX_LENGTH = 2_000;
    private static final EnumSet<WorkCaseStatus> ELIGIBLE_STATUSES = EnumSet.of(
            WorkCaseStatus.ACCEPTED,
            WorkCaseStatus.READY,
            WorkCaseStatus.IN_PROGRESS,
            WorkCaseStatus.CHECK_OUT_MISSING,
            WorkCaseStatus.COMPLETED,
            WorkCaseStatus.NO_SHOW
    );

    private final WorkSettlementService workSettlementService;
    private final SettlementMapper settlementMapper;
    private final DisputeMapper disputeMapper;
    private final DisputeReviewQueueService reviewQueueService;

    @Override
    @Transactional
    public Long create(DisputeCreateCommand command) {
        NormalizedDispute normalized = validateAndNormalize(command);

        // 지급·환불과 같은 Work → Settlement → Dispute 잠금 순서를 유지합니다.
        WorkCaseEscrowSnapshot workCase =
                workSettlementService.lockEscrowContext(command.getWorkCaseId());
        requirePartyAndEligibleStatus(workCase, command.getRequesterUserId(), command.getRequesterRole());

        SettlementSnapshot settlement =
                settlementMapper.findByWorkCaseIdForUpdate(command.getWorkCaseId());
        if (settlement == null) {
            throw new IllegalStateException("분쟁 대상 근무의 정산 행이 없습니다.");
        }
        reviewQueueService.requireEnabled();

        if (!disputeMapper.findOpenIdsForUpdate(command.getWorkCaseId()).isEmpty()) {
            throw new DisputeAlreadyOpenException();
        }

        DisputeInsert insert = DisputeInsert.builder()
                .workCaseId(command.getWorkCaseId())
                .requesterUserId(command.getRequesterUserId())
                .title(normalized.title())
                .content(normalized.content())
                .build();
        try {
            if (disputeMapper.insertOpen(insert) != 1 || insert.getReportId() == null) {
                throw new IllegalStateException("분쟁 생성 결과가 올바르지 않습니다.");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new DisputeAlreadyOpenException();
        }

        SettlementStatus reviewSettlementStatus = settlement.getStatus();
        if (settlement.getStatus() == SettlementStatus.SCHEDULED) {
            if (settlementMapper.transitionScheduledToOnHold(settlement.getSettlementId()) != 1) {
                throw new IllegalStateException("정산 보류 상태 전이에 실패했습니다.");
            }
            reviewSettlementStatus = SettlementStatus.ON_HOLD;
        }
        reviewQueueService.enqueue(DisputeReviewEnqueueCommand.builder()
                .disputeId(insert.getReportId())
                .title(normalized.title())
                .content(normalized.content())
                .workCase(workCase)
                .settlementStatus(reviewSettlementStatus)
                .build());
        return insert.getReportId();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<DisputeListItemResponse> findPage(
            long workCaseId,
            long requesterUserId,
            UserRole requesterRole,
            int page,
            int size) {
        WorkCaseEscrowSnapshot workCase = workSettlementService.findEscrowContext(workCaseId);
        requirePartyAndEligibleStatus(workCase, requesterUserId, requesterRole);

        long totalElements = disputeMapper.countByWorkCaseId(workCaseId);
        List<DisputeListItemResponse> content = disputeMapper.findPageByWorkCaseId(
                        workCaseId,
                        size,
                        PageRequests.offset(page, size))
                .stream()
                .map(DisputeServiceImpl::toResponse)
                .toList();
        return PageResponse.of(content, page, size, totalElements);
    }

    private static DisputeListItemResponse toResponse(DisputeListRow row) {
        return new DisputeListItemResponse(
                row.getReportId(),
                row.getTitle(),
                row.getContent(),
                row.getStatus(),
                row.getResolution(),
                row.getRequesterRole(),
                ApiTimes.toInstant(row.getCreatedAt()),
                ApiTimes.toInstant(row.getResolvedAt()),
                demoReviewFrom(row)
        );
    }

    private static DisputeDemoReviewResponse demoReviewFrom(DisputeListRow row) {
        if (row.getReviewSource() == null) {
            return null;
        }
        DisputeReviewExecutionStatus status = row.getReviewStatus();
        if (status == null) {
            log.warn("분쟁 검토 조회값에 실행 상태가 없습니다. reportId={}", row.getReportId());
            return unreadableReview(row);
        }
        if (status != DisputeReviewExecutionStatus.COMPLETED) {
            return new DisputeDemoReviewResponse(
                    row.getReviewSource(),
                    status.name(),
                    null,
                    List.of(),
                    null,
                    null,
                    ApiTimes.toInstant(row.getReviewedAt())
            );
        }
        try {
            DisputeReviewResult result = DisputeReviewResults.validate(new DisputeReviewResult(
                    row.getReviewDecision(),
                    DisputeReviewJsonCodec.readReasonCodes(row.getReviewReasonCodesJson()),
                    row.getReviewSummary(),
                    row.getReviewConfidence()
            ));
            return new DisputeDemoReviewResponse(
                    row.getReviewSource(),
                    status.name(),
                    result.getDecision().externalValue(),
                    result.getReasonCodes(),
                    result.getSummary(),
                    result.getConfidence(),
                    ApiTimes.toInstant(row.getReviewedAt())
            );
        } catch (RuntimeException corruptedReview) {
            // 쓰기 시점 검증과 DB CHECK를 통과하지 못한 Legacy/손상 행 하나가 Page 전체를 막지 않습니다.
            log.warn("분쟁 검토 조회값을 안전하게 축소합니다. reportId={}",
                    row.getReportId(), corruptedReview);
            return unreadableReview(row);
        }
    }

    private static DisputeDemoReviewResponse unreadableReview(DisputeListRow row) {
        return new DisputeDemoReviewResponse(
                row.getReviewSource(),
                DisputeReviewExecutionStatus.FAILED.name(),
                null,
                List.of(),
                null,
                null,
                ApiTimes.toInstant(row.getReviewedAt())
        );
    }

    private static NormalizedDispute validateAndNormalize(DisputeCreateCommand command) {
        if (command == null
                || command.getWorkCaseId() == null
                || command.getWorkCaseId() <= 0
                || command.getRequesterUserId() == null
                || command.getRequesterUserId() <= 0
                || command.getRequesterRole() == null) {
            throw new ValidationException("분쟁 신고 정보를 확인해 주세요.");
        }
        String title = normalize(command.getTitle());
        String content = normalize(command.getContent());
        validateLength(title, TITLE_MAX_LENGTH, "title", "제목은 1자 이상 100자 이하여야 합니다.");
        validateLength(
                content,
                CONTENT_MAX_LENGTH,
                "content",
                "경위는 1자 이상 2000자 이하여야 합니다."
        );
        return new NormalizedDispute(title, content);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static void validateLength(String value, int maxLength, String field, String message) {
        if (value.isEmpty() || value.length() > maxLength) {
            throw new ValidationException("입력값을 확인해 주세요.", field, message);
        }
    }

    private static void requirePartyAndEligibleStatus(
            WorkCaseEscrowSnapshot workCase,
            long requesterUserId,
            UserRole requesterRole) {
        if (workCase == null || !isParty(workCase, requesterUserId, requesterRole)) {
            throw new ResourceNotFoundException("근무 건을 찾을 수 없습니다.");
        }
        if (!ELIGIBLE_STATUSES.contains(workCase.getStatus())) {
            throw new ConflictException("현재 근무 상태에서는 분쟁을 신고하거나 조회할 수 없습니다.");
        }
    }

    private static boolean isParty(
            WorkCaseEscrowSnapshot workCase,
            long requesterUserId,
            UserRole requesterRole) {
        if (requesterRole == UserRole.OWNER) {
            return Long.valueOf(requesterUserId).equals(workCase.getEmployerId());
        }
        return requesterRole == UserRole.WORKER
                && Long.valueOf(requesterUserId).equals(workCase.getWorkerId());
    }

    private record NormalizedDispute(String title, String content) {
    }
}
