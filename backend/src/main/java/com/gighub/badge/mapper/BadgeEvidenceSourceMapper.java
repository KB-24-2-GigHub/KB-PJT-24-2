package com.gighub.badge.mapper;

import com.gighub.badge.mapper.result.BadgeEvidenceCountsRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 신뢰 배지 산정에 필요한 원천 이력(정산·분쟁·근무·출퇴근)을 역할별로 집계합니다.
 *
 * <p>Settlement·Work·Attendance가 소유한 테이블을 읽기 전용으로 JOIN하는
 * {@code MODULE_BOUNDARIES.md} QX-006 Query 예외입니다. SELECT만 수행하고, 이 Mapper
 * 밖으로 다른 모듈 Row나 SQL을 재사용하지 않습니다.</p>
 */
@Mapper
public interface BadgeEvidenceSourceMapper {

    BadgeEvidenceCountsRow countOwnerEvidence(@Param("ownerId") Long ownerId);

    BadgeEvidenceCountsRow countWorkerEvidence(@Param("workerId") Long workerId);
}