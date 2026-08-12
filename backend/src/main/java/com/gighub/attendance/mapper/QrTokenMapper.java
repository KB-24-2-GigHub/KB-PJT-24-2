package com.gighub.attendance.mapper;

import com.gighub.attendance.mapper.param.QrTokenInsertParam;
import com.gighub.attendance.mapper.result.QrTokenRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 사업장 고정 QR 저장과 조회 SQL 진입점입니다. */
@Mapper
public interface QrTokenMapper {

    /**
     * 사업장의 활성 고정 QR 한 건을 조회합니다.
     *
     * <p>{@code uk_qr_tokens_workplace_active} 때문에 결과는 0건 또는 1건뿐입니다.</p>
     *
     * @param workplaceId 대상 사업장 식별자
     * @return 활성 QR이 없으면 {@code null}
     */
    QrTokenRow findActiveByWorkplaceId(@Param("workplaceId") Long workplaceId);

    /**
     * 활성 고정 QR을 잠그고 읽습니다. 반드시 Transaction 안에서 부릅니다.
     *
     * <p>승인된 잠금 순서 {@code workplaces -> qr_tokens -> work_cases}의 두 번째
     * 단계입니다. 재발급도 workplaces를 먼저 잠근 뒤 이 행을 바꾸므로, 스캔이 같은 순서로
     * 줄을 서면 폐기된 QR이 뒤늦게 성공하지 않습니다.</p>
     *
     * @return 활성 QR이 없으면 {@code null}
     */
    QrTokenRow findActiveByWorkplaceIdForUpdate(@Param("workplaceId") Long workplaceId);

    /**
     * 활성 고정 QR 한 건을 저장하고 생성된 식별자를 {@code param.id}에 채웁니다.
     *
     * <p>같은 사업장에 활성 QR이 이미 있으면 부분 유니크가 {@code DuplicateKeyException}을
     * 던집니다. 사전 조회로는 동시 발급을 막지 못하므로 이 예외를 호출자가 승인된 충돌
     * 응답으로 바꿉니다.</p>
     */
    int insertActive(QrTokenInsertParam param);

    /**
     * 사업장의 활성 고정 QR을 폐기합니다.
     *
     * <p>{@code ck_qr_tokens_revoked_at}이 상태와 폐기 시각을 함께 요구하므로 한 UPDATE에서
     * 둘 다 세팅합니다.</p>
     *
     * @param workplaceId 대상 사업장 식별자
     * @return 폐기한 행 수. 활성 QR이 없었으면 {@code 0}
     */
    int revokeActiveByWorkplaceId(@Param("workplaceId") Long workplaceId);
}
