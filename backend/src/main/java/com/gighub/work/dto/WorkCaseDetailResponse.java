package com.gighub.work.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.gighub.common.api.ApiTimes;
import com.gighub.work.domain.WorkCaseStatus;

import lombok.Getter;

/**
 * 근무 상세 응답입니다.
 *
 * <p>API_SPEC 4.0.0이 고정한 닫힌 필드 집합만 두고, 문서 본문·서명 증거·좌표·인증 반경·
 * 전화번호·Capability는 포함하지 않습니다. {@code worker}·{@code latestInvitation}·
 * {@code contract}·{@code escrow}·{@code settlement}는 근거 행이 없으면 객체 전체가
 * {@code null}이고, {@code attendance}만 항상 객체입니다.</p>
 */
@Getter
public final class WorkCaseDetailResponse {

    private final Long workCaseId;
    private final String title;
    private final LocalDate workDate;
    private final Instant startsAt;
    private final Instant endsAt;
    private final Integer breakMinutes;
    private final Boolean breakPaid;
    private final Long dailyWage;
    private final WorkCaseStatus status;
    private final Integer termsVersion;
    private final String workplaceName;
    private final String workplaceAddress;
    private final WorkerSummary worker;
    private final InvitationSummary latestInvitation;
    private final ContractSummary contract;
    private final AttendanceSummary attendance;
    private final EscrowSummary escrow;
    private final SettlementSummary settlement;

    private WorkCaseDetailResponse(
            Long workCaseId,
            String title,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            Integer breakMinutes,
            Boolean breakPaid,
            Long dailyWage,
            WorkCaseStatus status,
            Integer termsVersion,
            String workplaceName,
            String workplaceAddress,
            WorkerSummary worker,
            InvitationSummary latestInvitation,
            ContractSummary contract,
            AttendanceSummary attendance,
            EscrowSummary escrow,
            SettlementSummary settlement) {
        this.workCaseId = workCaseId;
        this.title = title;
        // workDate는 저장 컬럼이 아니라 startsAt에서 파생합니다(API_SPEC 4.0.0).
        this.workDate = startsAt.toLocalDate();
        this.startsAt = ApiTimes.toInstant(startsAt);
        this.endsAt = ApiTimes.toInstant(endsAt);
        this.breakMinutes = breakMinutes;
        this.breakPaid = breakPaid;
        this.dailyWage = dailyWage;
        this.status = status;
        this.termsVersion = termsVersion;
        this.workplaceName = workplaceName;
        this.workplaceAddress = workplaceAddress;
        this.worker = worker;
        this.latestInvitation = latestInvitation;
        this.contract = contract;
        this.attendance = attendance;
        this.escrow = escrow;
        this.settlement = settlement;
    }

    public static WorkCaseDetailResponse of(
            Long workCaseId,
            String title,
            LocalDateTime startsAt,
            LocalDateTime endsAt,
            Integer breakMinutes,
            Boolean breakPaid,
            Long dailyWage,
            WorkCaseStatus status,
            Integer termsVersion,
            String workplaceName,
            String workplaceAddress,
            WorkerSummary worker,
            InvitationSummary latestInvitation,
            ContractSummary contract,
            AttendanceSummary attendance,
            EscrowSummary escrow,
            SettlementSummary settlement) {
        return new WorkCaseDetailResponse(
                workCaseId,
                title,
                startsAt,
                endsAt,
                breakMinutes,
                breakPaid,
                dailyWage,
                status,
                termsVersion,
                workplaceName,
                workplaceAddress,
                worker,
                latestInvitation,
                contract,
                attendance,
                escrow,
                settlement);
    }

    @Getter
    public static final class WorkerSummary {

        private final Long workerId;
        private final String name;
        private final WorkerBadge badge;

        private WorkerSummary(Long workerId, String name, WorkerBadge badge) {
            this.workerId = workerId;
            this.name = name;
            this.badge = badge;
        }

        public static WorkerSummary of(Long workerId, String name, WorkerBadge badge) {
            return new WorkerSummary(workerId, name, badge);
        }
    }

    /**
     * 매칭된 WORKER의 현재 승인 신뢰 Badge입니다.
     *
     * <p>초대 응답의 {@code ownerBadge}(OwnerBadgeResponse)와 같은 관례입니다 — 활성 Badge가
     * 없으면(0단계) 이 객체 대신 {@code badge: null}을 반환합니다. 빈 객체나 기본 등급으로
     * 채우면 받는 쪽이 "Badge 없음"과 "가장 낮은 Badge"를 구분할 수 없습니다.</p>
     */
    @Getter
    public static final class WorkerBadge {

        private final String badgeType;
        private final Integer level;

        private WorkerBadge(String badgeType, Integer level) {
            this.badgeType = badgeType;
            this.level = level;
        }

        public static WorkerBadge of(String badgeType, Integer level) {
            return new WorkerBadge(badgeType, level);
        }
    }

    @Getter
    public static final class InvitationSummary {

        private final String status;
        private final Integer termsVersion;
        private final Instant expiresAt;

        private InvitationSummary(String status, Integer termsVersion, LocalDateTime expiresAt) {
            this.status = status;
            this.termsVersion = termsVersion;
            this.expiresAt = ApiTimes.toInstant(expiresAt);
        }

        public static InvitationSummary of(String status, Integer termsVersion, LocalDateTime expiresAt) {
            return new InvitationSummary(status, termsVersion, expiresAt);
        }
    }

    @Getter
    public static final class ContractSummary {

        private final Long contractId;
        private final Long documentId;
        private final Integer sourceTermsVersion;
        private final Instant acceptedAt;

        private ContractSummary(
                Long contractId,
                Long documentId,
                Integer sourceTermsVersion,
                LocalDateTime acceptedAt) {
            this.contractId = contractId;
            this.documentId = documentId;
            this.sourceTermsVersion = sourceTermsVersion;
            this.acceptedAt = ApiTimes.toInstant(acceptedAt);
        }

        public static ContractSummary of(
                Long contractId,
                Long documentId,
                Integer sourceTermsVersion,
                LocalDateTime acceptedAt) {
            return new ContractSummary(contractId, documentId, sourceTermsVersion, acceptedAt);
        }
    }

    @Getter
    public static final class AttendanceSummary {

        private final Instant checkedInAt;
        private final Instant checkedOutAt;

        /**
         * 근태 기록이 하나도 없는 근무를 빈 요약으로 받습니다.
         *
         * <p>{@code findAttendanceTimestamps}는 {@code MAX(...)} 집계라 행 자체는 항상
         * 하나지만 두 컬럼이 모두 {@code NULL}입니다. MyBatis는 생성자 resultMap에서 모든
         * 컬럼이 {@code NULL}인 행을 {@code null} 객체로 매핑하므로(기본
         * {@code returnInstanceForEmptyRow=false}) 여기로 {@code null}이 들어옵니다.
         * 출근 전 근무가 대부분이라 이를 막지 않으면 상세 조회가 상시 실패합니다.</p>
         *
         * <p>{@code attendance}만 항상 객체라는 응답 계약은 그대로 유지하고, 값만 비웁니다.</p>
         */
        private AttendanceSummary(LocalDateTime checkedInAt, LocalDateTime checkedOutAt) {
            this.checkedInAt = ApiTimes.toInstant(checkedInAt);
            this.checkedOutAt = ApiTimes.toInstant(checkedOutAt);
        }

        public static AttendanceSummary of(LocalDateTime checkedInAt, LocalDateTime checkedOutAt) {
            return new AttendanceSummary(checkedInAt, checkedOutAt);
        }
    }

    @Getter
    public static final class EscrowSummary {

        private final String status;
        private final Long amount;

        private EscrowSummary(String status, Long amount) {
            this.status = status;
            this.amount = amount;
        }

        public static EscrowSummary of(String status, Long amount) {
            return new EscrowSummary(status, amount);
        }
    }

    @Getter
    public static final class SettlementSummary {

        private final String status;
        private final Long amount;
        private final Long originalEscrowAmount;
        private final Long workerPaidAmount;
        private final Long ownerRefundAmount;
        private final Long deductionAmount;
        private final Long deductionBaseMinutes;
        private final Long lateMinutes;
        private final Long earlyLeaveMinutes;
        private final String calculationReason;
        private final String calculationVersion;
        private final Instant calculatedAt;
        private final Instant dueAt;
        private final Instant completedAt;

        private SettlementSummary(
                String status,
                Long amount,
                Long workerPaidAmount,
                Long ownerRefundAmount,
                Long deductionBaseMinutes,
                Long lateMinutes,
                Long earlyLeaveMinutes,
                String calculationReason,
                String calculationVersion,
                LocalDateTime calculatedAt,
                LocalDateTime dueAt,
                LocalDateTime completedAt) {
            this.status = status;
            this.amount = amount;
            this.originalEscrowAmount = amount;
            this.workerPaidAmount = workerPaidAmount;
            this.ownerRefundAmount = ownerRefundAmount;
            this.deductionAmount = ownerRefundAmount;
            this.deductionBaseMinutes = deductionBaseMinutes;
            this.lateMinutes = lateMinutes;
            this.earlyLeaveMinutes = earlyLeaveMinutes;
            this.calculationReason = calculationReason;
            this.calculationVersion = calculationVersion;
            this.calculatedAt = ApiTimes.toInstant(calculatedAt);
            this.dueAt = ApiTimes.toInstant(dueAt);
            this.completedAt = ApiTimes.toInstant(completedAt);
        }

        public static SettlementSummary of(
                String status,
                Long amount,
                Long workerPaidAmount,
                Long ownerRefundAmount,
                Long deductionBaseMinutes,
                Long lateMinutes,
                Long earlyLeaveMinutes,
                String calculationReason,
                String calculationVersion,
                LocalDateTime calculatedAt,
                LocalDateTime dueAt,
                LocalDateTime completedAt) {
            return new SettlementSummary(
                    status,
                    amount,
                    workerPaidAmount,
                    ownerRefundAmount,
                    deductionBaseMinutes,
                    lateMinutes,
                    earlyLeaveMinutes,
                    calculationReason,
                    calculationVersion,
                    calculatedAt,
                    dueAt,
                    completedAt);
        }
    }
}
