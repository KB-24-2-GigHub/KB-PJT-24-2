package com.gighub.work.mapper;

import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.contract.WorkCaseEscrowSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface WorkMapper {
    WorkCaseEscrowSnapshot getEscrowContextForUpdate(
            @Param("workCaseId") Long workCaseId);

    /** 잠근 Work Case를 Domain이 승인한 expected-state에서 목표 상태로 전이합니다. */
    int updateWorkStatus(@Param("workCaseId") Long workCaseId,
                         @Param("fromStatuses") List<WorkCaseStatus> fromStatuses,
                         @Param("toStatus") WorkCaseStatus toStatus);

    Long getWorkerIdByWorkCaseId(@Param("workCaseId") Long workCaseId);

    Long getAgreedWageByWorkCaseId(@Param("workCaseId") Long workCaseId);
}
