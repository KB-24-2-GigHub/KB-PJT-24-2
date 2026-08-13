package com.gighub.workplace.mapper;

import java.math.BigDecimal;
import java.util.List;

import com.gighub.workplace.mapper.param.WorkplaceInsertParam;
import com.gighub.workplace.mapper.result.WorkplaceListRow;
import com.gighub.workplace.service.result.WorkplaceLocationSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 사업장 등록과 소유 사업장 조회 SQL 진입점입니다. */
@Mapper
public interface WorkplaceMapper {

    /**
     * 사업장 한 건을 저장하고 생성된 식별자를 {@code param.id}에 채웁니다.
     *
     * <p>사업자등록번호 중복은 여기서 미리 조회하지 않고 Unique 제약이 던지는 예외를
     * Service가 승인된 충돌 응답으로 바꿉니다. 조회 후 저장 사이에 다른 요청이 끼어들 수
     * 있어 사전 조회로는 동시 등록을 막지 못합니다.</p>
     */
    int insert(WorkplaceInsertParam param);

    /**
     * 인증 OWNER가 소유한 관리 대상 사업장 한 Page를 조회합니다.
     *
     * <p>승인 계약(DEC-WORKPLACE-LIST)에 따라 {@code ACTIVE}와 {@code INACTIVE}만 반환하고
     * {@code DELETED}는 제외합니다. 제외 조건을 {@code <> 'DELETED'}로 쓰지 않는 이유는 나중에
     * 상태가 추가될 때 그 값이 검토 없이 목록에 노출되기 때문입니다. 허용 목록으로 두면 새 상태는
     * 기본적으로 숨겨지고, 노출 여부를 계약으로 결정한 뒤에만 열립니다.</p>
     *
     * <p>{@code ownerUserId}는 요청 값이 아니라 인증 Principal에서 채웁니다. 소유권 조건이
     * 목록 SQL 자체에 있어야 다른 OWNER의 사업장이 Page에 섞이지 않습니다.</p>
     *
     * @param ownerUserId 인증 Principal의 사용자 식별자
     * @param size        가져올 최대 행 수
     * @param offset      건너뛸 행 수
     * @return 정렬이 고정된 사업장 목록. 해당 Page에 행이 없으면 빈 List
     */
    List<WorkplaceListRow> findPageByOwnerUserId(
            @Param("ownerUserId") Long ownerUserId,
            @Param("size") int size,
            @Param("offset") long offset);

    /**
     * 같은 조건으로 전체 건수를 셉니다. Page Metadata의 {@code totalElements}에 씁니다.
     *
     * <p>목록 조회와 조건이 어긋나면 마지막 Page가 비거나 총 건수가 실제와 달라지므로 두 SQL의
     * {@code WHERE}는 항상 같이 바꿉니다.</p>
     *
     * @param ownerUserId 인증 Principal의 사용자 식별자
     * @return {@code DELETED}를 제외한 소유 사업장 수
     */
    int countByOwnerUserId(@Param("ownerUserId") Long ownerUserId);

    /**
     * OWNER 온보딩 상태를 계산하기 위해 소유한 {@code ACTIVE} 사업장 수만 셉니다.
     *
     * <p>DEC-OWNER-ONBOARDING은 활성 사업장이 없을 때만 설정이 필요하다고 판단하므로
     * {@code INACTIVE}를 제외합니다. 목록 Metadata용 {@link #countByOwnerUserId(Long)}는
     * DEC-WORKPLACE-LIST에 따라 {@code ACTIVE}와 {@code INACTIVE}를 모두 세며, 두 기준은
     * 의도적으로 다릅니다.</p>
     *
     * @param ownerUserId 인증 Principal의 사용자 식별자
     * @return 소유한 {@code ACTIVE} 사업장 수
     */
    int countActiveByOwnerUserId(@Param("ownerUserId") Long ownerUserId);

    /**
     * 인증 OWNER가 소유한 {@code ACTIVE} 사업장인지 셉니다.
     *
     * <p>없는 사업장과 다른 OWNER의 사업장을 구분하지 않습니다. 구분하면 사업장 식별자의
     * 존재 여부가 비소유자에게 드러납니다.</p>
     *
     * @param workplaceId 확인할 사업장 식별자
     * @param ownerUserId 인증 Principal의 사용자 식별자
     * @return 소유한 {@code ACTIVE} 사업장이면 {@code 1}, 아니면 {@code 0}
     */
    int countOwnedActiveById(
            @Param("workplaceId") Long workplaceId,
            @Param("ownerUserId") Long ownerUserId);

    /**
     * 인증 OWNER가 소유한 {@code ACTIVE} 사업장을 잠그며 조회합니다.
     *
     * <p>QR을 바꾸는 흐름은 항상 {@code workplaces}를 먼저 잠그고 {@code qr_tokens}를
     * 건드립니다. {@code qr_tokens}만 잠그면 두 요청이 서로 다른 순서로 행을 잡아 교착할 수
     * 있습니다.</p>
     *
     * @param workplaceId 잠글 사업장 식별자
     * @param ownerUserId 인증 Principal의 사용자 식별자
     * @return 소유한 {@code ACTIVE} 사업장 식별자. 아니면 {@code null}
     */
    Long findOwnedActiveIdForUpdate(
            @Param("workplaceId") Long workplaceId,
            @Param("ownerUserId") Long ownerUserId);

    /**
     * 소유권과 무관하게 활성 사업장 행을 잠그고 현재 좌표를 읽습니다.
     *
     * <p>근태 스캔은 사업장을 소유하지 않은 WORKER가 호출하므로 소유자 조건을 두지
     * 않습니다. 거리 판정 기준이 현재 좌표라서 잠금과 좌표 조회를 한 문장에서 처리합니다.</p>
     *
     * @return 활성 사업장이 아니면 {@code null}
     */
    WorkplaceLocationSnapshot findActiveLocationForUpdate(
            @Param("workplaceId") Long workplaceId);

    /**
     * 인증 OWNER가 소유한 {@code ACTIVE} 사업장 행을 잠그고 현재 좌표를 읽습니다.
     *
     * <p>현장 위치 확정은 OWNER 본인만 호출하므로 소유자 조건을 둡니다. 없는 사업장과
     * 다른 OWNER의 사업장을 구분하지 않아야 하므로 {@link #countOwnedActiveById}와 같은
     * 조건을 씁니다.</p>
     *
     * @return 소유한 {@code ACTIVE} 사업장이 아니면 {@code null}
     */
    WorkplaceLocationSnapshot findOwnedActiveLocationForUpdate(
            @Param("workplaceId") Long workplaceId,
            @Param("ownerUserId") Long ownerUserId);

    /**
     * 좌표가 비어 있는 사업장 행에만 현장 위치를 확정합니다.
     *
     * <p>{@code WHERE latitude IS NULL}을 조건에 두어, 잠금과 확정 사이에 다른 요청이 먼저
     * 확정했더라도 이 UPDATE가 조용히 값을 덮어쓰지 않습니다. 호출자는 잠금 조회에서 이미
     * 비어 있음을 확인했지만, 이 방어는 그 확인 이후 로직이 실수로 순서를 바꿔도 안전하도록
     * 남겨 둡니다.</p>
     *
     * @return 실제로 갱신된 행 수. 이미 확정돼 있었으면 {@code 0}
     */
    int confirmCoordinates(
            @Param("workplaceId") Long workplaceId,
            @Param("latitude") BigDecimal latitude,
            @Param("longitude") BigDecimal longitude);
}
