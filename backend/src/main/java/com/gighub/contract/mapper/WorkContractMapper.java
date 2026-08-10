package com.gighub.contract.mapper;

import com.gighub.contract.mapper.param.WorkContractInsertParam;
import com.gighub.contract.mapper.result.ContractPartyNamesRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 근로계약 Snapshot 행의 저장과 조회 SQL 진입점입니다. */
@Mapper
public interface WorkContractMapper {

    /**
     * 계약 Snapshot에 굳힐 당사자 이름을 읽습니다.
     *
     * @param employerId 근무를 등록한 OWNER 식별자
     * @param workerId   수락한 WORKER 식별자
     * @return 두 이름. 어느 한쪽이라도 없으면 {@code null}
     */
    ContractPartyNamesRow findPartyNames(
            @Param("employerId") long employerId,
            @Param("workerId") long workerId);

    /**
     * 계약 Snapshot 한 행을 저장하고 생성된 식별자를 {@code param.id}에 채웁니다.
     *
     * <p>반드시 근무 행의 매칭·상태 전이를 <b>먼저</b> 적용한 뒤에 부릅니다. 복합 FK
     * {@code fk_work_contracts_case_parties_wage}가 {@code (work_case_id, employer_id,
     * worker_id, agreed_wage)}를 근무 행과 대조하므로, 근무에 WORKER가 아직 없으면 이 INSERT가
     * 참조 무결성 위반으로 실패합니다.</p>
     *
     * <p>{@code uk_work_contracts_work_case_id}가 근무당 한 건을 보장합니다. 중복 수락이
     * 상태 검증을 모두 통과하더라도 이 제약이 마지막 방어선이 됩니다.</p>
     */
    int insert(WorkContractInsertParam param);
}
