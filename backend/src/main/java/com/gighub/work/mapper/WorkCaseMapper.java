package com.gighub.work.mapper;

import java.util.List;

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
import com.gighub.work.mapper.result.WorkCaseStatusCountRow;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * OWNER 근무 Case의 조회·저장·상태 전이 SQL 진입점입니다.
 *
 * <p>{@code work_cases} DML은 이 Mapper 하나가 소유합니다. 다른 모듈은 공개 Work Command
 * Service를 통해 상태를 바꾸며 Mapper나 persistence 타입을 직접 가져가지 않습니다.</p>
 *
 * <p>상태 조건을 Java의 if 문이 아니라 {@code WHERE}에 두어 확인과 변경이 한 문장 안에서
 * 원자적으로 처리되게 합니다. 호출부는 변경된 행 수가 0인지로 상태가 어긋났음을 판단합니다.</p>
 */
@Mapper
public interface WorkCaseMapper {

    /**
     * 인증 OWNER가 소유한 {@code ACTIVE} 사업장의 Snapshot 원본을 읽습니다.
     *
     * <p>소유권 확인과 Snapshot 조회를 한 번에 처리합니다. {@code ownerUserId}는 요청 값이
     * 아니라 인증 Principal에서 채웁니다.</p>
     *
     * @return 소유한 활성 사업장이 아니면 {@code null}
     */
    OwnedWorkplaceSnapshotRow findOwnedActiveWorkplace(
            @Param("workplaceId") Long workplaceId,
            @Param("ownerUserId") Long ownerUserId);

    /**
     * 근무 행을 잠그고 상태 판단에 필요한 값을 읽습니다. 반드시 Transaction 안에서 부릅니다.
     *
     * @return 해당 근무가 없으면 {@code null}
     */
    WorkCaseLockRow lockById(@Param("workCaseId") Long workCaseId);

    /**
     * 근무 {@code DRAFT} 한 건을 저장하고 생성된 식별자를 {@code param.workCaseId}에 채웁니다.
     *
     * <p>사업장 소유권은 미리 조회한 결과가 아니라 복합 FK
     * {@code fk_work_cases_employer_workplace}가 최종적으로 보장합니다.</p>
     */
    int insert(WorkCaseInsertParam param);

    /**
     * {@code DRAFT} 근무의 조건을 바꾸고 {@code terms_version}을 정확히 1 증가시킵니다.
     *
     * @return 변경된 행 수. {@code DRAFT}가 아니거나 없으면 0
     */
    int updateDraftTerms(WorkCaseTermsUpdateParam param);

    /**
     * 이전 조건으로 발급된 {@code PENDING} 초대를 모두 철회합니다.
     *
     * <p>WORK-005가 조건 변경과 함께 요구하는 전이이므로 조건 수정과 같은 Transaction에서
     * 부릅니다. 행을 지우지 않고 상태만 바꾸는 이유는 초대 이력이 삭제·취소 판단의 근거로
     * 남아야 하기 때문입니다.</p>
     *
     * @return 철회된 초대 수
     */
    /**
     * 상태와 무관하게 해당 근무의 전체 초대 수를 셉니다.
     *
     * <p>WORK-006의 "초대 이력이 전혀 없는 {@code DRAFT}만 물리 삭제한다"를 판정하는 값이라
     * {@code PENDING}만 세지 않습니다.</p>
     */
    int countInvitations(@Param("workCaseId") Long workCaseId);

    /**
     * 초대 이력이 없는 {@code DRAFT}를 물리 삭제합니다.
     *
     * @return 삭제된 행 수. {@code DRAFT}가 아니면 0
     */
    int deleteDraft(@Param("workCaseId") Long workCaseId);

    /**
     * 초대 이력이 있는 {@code DRAFT}를 {@code CANCELED}로 전이하고 시각을 남깁니다.
     *
     * @return 변경된 행 수. {@code DRAFT}가 아니면 0
     */
    int cancelDraft(@Param("workCaseId") Long workCaseId);

    /** 수락 시 미매칭 DRAFT에 WORKER를 배정하고 ACCEPTED로 전이합니다. */
    int assignWorkerAndAccept(
            @Param("workCaseId") long workCaseId,
            @Param("workerId") long workerId);

    /** Settlement outer Transaction이 가장 먼저 잠그는 최소 Work Snapshot입니다. */
    WorkCaseEscrowSnapshot getEscrowContextForUpdate(
            @Param("workCaseId") Long workCaseId);

    /** Domain이 승인한 expected-state 목록에서 목표 상태로 원자 전이합니다. */
    int updateWorkStatus(
            @Param("workCaseId") Long workCaseId,
            @Param("fromStatuses") List<WorkCaseStatus> fromStatuses,
            @Param("toStatus") WorkCaseStatus toStatus);

    /**
     * 사업장의 상태별 근무 건수를 집계합니다.
     *
     * <p>소유권 조건을 집계 SQL 자체에 두어 다른 OWNER의 근무가 건수에 섞이지 않게 합니다. 이
     * 조건만으로는 "소유하지 않음"과 "소유했지만 근무가 0건"을 구분할 수 없어, 호출부가 먼저
     * {@link #existsOwnedManageableWorkplace}로 소유권을 확인합니다.</p>
     *
     * @return 건수가 1 이상인 상태만. 0건 상태는 호출부가 채웁니다
     */
    List<WorkCaseStatusCountRow> countByStatus(
            @Param("workplaceId") Long workplaceId,
            @Param("ownerUserId") Long ownerUserId);

    /**
     * 인증 OWNER가 소유하고 관리 대상인 사업장인지 확인합니다.
     *
     * <p>{@code ACTIVE}·{@code INACTIVE}만 허용하고 {@code DELETED}는 제외합니다. 제외 조건을
     * {@code <> 'DELETED'}로 쓰지 않는 이유는 나중에 상태가 추가될 때 그 값이 검토 없이
     * 조회 가능한 것으로 노출되기 때문입니다. 허용 목록으로 두면 새 상태는 기본적으로
     * 숨겨집니다.</p>
     */
    boolean existsOwnedManageableWorkplace(
            @Param("workplaceId") Long workplaceId,
            @Param("ownerUserId") Long ownerUserId);

    /**
     * 조건에 맞는 근무 목록 한 Page를 정렬이 고정된 순서로 조회합니다.
     *
     * <p>정렬은 {@code starts_at DESC, id DESC}로 고정됩니다. API_SPEC 4.0.0이 별도 정렬
     * Query를 받지 않기로 확정했습니다.</p>
     */
    List<WorkCaseListRow> findPageByFilters(WorkCaseListQuery query);

    /**
     * 같은 조건으로 전체 건수를 셉니다. Page Metadata의 {@code totalElements}에 씁니다.
     *
     * <p>{@link #findPageByFilters}와 조건이 어긋나면 마지막 Page가 비거나 총 건수가 실제와
     * 달라지므로 두 쿼리는 같은 {@link WorkCaseListQuery}를 받고 XML의 {@code WHERE}를
     * 공유합니다.</p>
     */
    long countByFilters(WorkCaseListQuery query);

    /**
     * 근무 상세 본문과 매칭 WORKER를 함께 읽습니다.
     *
     * <p>좌표·인증 반경·전화번호는 API_SPEC 4.0.0이 상세 응답에서 제외했으므로 SELECT 자체에
     * 넣지 않습니다.</p>
     *
     * @return 해당 근무가 없으면 {@code null}
     */
    WorkCaseDetailRow findDetailRow(@Param("workCaseId") Long workCaseId);

    /**
     * 조건 Version과 무관하게 생성 시각·ID 내림차순 최신 초대 한 건을 읽습니다.
     *
     * @return 초대 이력이 없으면 {@code null}
     */
    LatestInvitationRow findLatestInvitation(@Param("workCaseId") Long workCaseId);

    /**
     * 근무 계약과 연결 계약서 문서를 함께 읽습니다.
     *
     * <p>{@code work_contracts}에는 문서 식별자가 없어 {@code documents.work_case_id}와
     * {@code document_type='EMPLOYMENT_CONTRACT'}로 LEFT JOIN합니다. 계약은 있는데
     * {@code documentId}가 {@code null}이면 계약서 생성이 중간에 끊긴 손상 상태이며, 호출부가
     * 이 경우를 계약 없음과 구분해 처리합니다.</p>
     *
     * @return 계약이 없으면 {@code null}
     */
    ContractDetailRow findContractDetail(@Param("workCaseId") Long workCaseId);

    /**
     * 근무의 에스크로 상태와 금액을 읽습니다.
     *
     * @return 에스크로 행이 없으면 {@code null}
     */
    EscrowSummaryRow findEscrow(@Param("workCaseId") Long workCaseId);

    /**
     * 근무의 정산 상태·금액과 예정·완료 시각을 읽습니다.
     *
     * @return 정산 행이 없으면 {@code null}
     */
    SettlementSummaryRow findSettlement(@Param("workCaseId") Long workCaseId);

    /**
     * 성공 출근·퇴근 시각을 조건부 집계 하나로 함께 읽습니다.
     *
     * <p>근태 행이 전혀 없어도 두 필드가 모두 {@code null}인 행 하나를 돌려줍니다. API_SPEC
     * 4.0.0이 {@code attendance}를 항상 객체로 응답하도록 고정했으므로 {@code null} 판정을
     * 호출부에 넘기지 않습니다.</p>
     */
    AttendanceSummaryRow findAttendanceTimestamps(@Param("workCaseId") Long workCaseId);
}
