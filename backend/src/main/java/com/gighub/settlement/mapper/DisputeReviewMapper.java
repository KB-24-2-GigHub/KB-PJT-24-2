package com.gighub.settlement.mapper;

import com.gighub.settlement.mapper.command.DisputeReviewCompletion;
import com.gighub.settlement.mapper.command.DisputeReviewInsert;
import com.gighub.settlement.mapper.result.DisputeReviewCandidate;
import com.gighub.settlement.mapper.result.DisputeReviewExecutionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** Settlement 모듈이 소유한 비동기 AI 검토 감사 행 Mapper입니다. */
@Mapper
public interface DisputeReviewMapper {

    int insertPending(DisputeReviewInsert insert);

    LocalDateTime currentDatabaseTime();

    List<DisputeReviewCandidate> findCandidates(
            @Param("eligibilityTime") LocalDateTime eligibilityTime,
            @Param("limit") int limit);

    DisputeReviewExecutionRow findByIdForUpdate(@Param("reviewId") Long reviewId);

    long countByDisputeId(@Param("disputeId") Long disputeId);

    int claimPending(
            @Param("reviewId") Long reviewId,
            @Param("requestKey") String requestKey,
            @Param("leaseUntil") LocalDateTime leaseUntil);

    int complete(DisputeReviewCompletion completion);

    int markFailed(
            @Param("reviewId") Long reviewId,
            @Param("requestKey") String requestKey,
            @Param("failureCode") String failureCode);
}
