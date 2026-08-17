package com.gighub.settlement.mapper;

import com.gighub.settlement.mapper.command.DisputeInsert;
import com.gighub.settlement.mapper.result.DisputeListRow;
import com.gighub.settlement.mapper.result.DisputeSnapshot;
import com.gighub.settlement.domain.DisputeStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Settlement 모듈이 소유한 disputes 테이블의 쓰기·조회 Mapper입니다. */
@Mapper
public interface DisputeMapper {

    /** 지급과 같은 잠금 순서를 유지한 뒤 열린 분쟁 행 또는 빈 구간을 잠급니다. */
    List<Long> findOpenIdsForUpdate(@Param("workCaseId") Long workCaseId);

    int insertOpen(DisputeInsert insert);

    DisputeSnapshot findByIdForUpdate(@Param("disputeId") Long disputeId);

    int transitionOpenToUnderReview(@Param("disputeId") Long disputeId);

    int transitionToClosed(
            @Param("disputeId") Long disputeId,
            @Param("targetStatus") DisputeStatus targetStatus,
            @Param("resolution") String resolution);

    long countByWorkCaseId(@Param("workCaseId") Long workCaseId);

    List<DisputeListRow> findPageByWorkCaseId(
            @Param("workCaseId") Long workCaseId,
            @Param("limit") int limit,
            @Param("offset") long offset);
}
