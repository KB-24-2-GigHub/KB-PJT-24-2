package com.gighub.workplace.mapper.param;

import java.math.BigDecimal;

import lombok.Builder;
import lombok.Getter;

/**
 * 사업장 부분 수정 UPDATE 파라미터입니다(SPEC-349-01).
 *
 * <p>어떤 Column을 SET에 넣을지는 값의 {@code null} 여부가 아니라 {@code *Provided}
 * 플래그가 정합니다. {@code detailAddress}는 {@code null}로 지우는 것이 정상 입력이라
 * 값으로는 "지움"과 "안 바꿈"을 구분할 수 없습니다.</p>
 *
 * <p>{@code expectedRoadAddress}는 좌표를 다시 확정할 때만 채웁니다. 주소 변환은 트랜잭션
 * 밖에서 끝나므로, 변환의 근거가 된 저장 주소를 UPDATE 조건에 넣어야 그 사이 주소를 바꾼
 * 다른 요청의 결과를 엉뚱한 좌표로 덮어쓰지 않습니다.</p>
 */
@Getter
@Builder
public class WorkplaceUpdateParam {

    private final Long workplaceId;
    private final Long ownerUserId;

    private final boolean nameProvided;
    private final String name;

    private final boolean roadAddressProvided;
    private final String roadAddress;

    private final boolean detailAddressProvided;
    private final String detailAddress;

    private final boolean phoneProvided;
    private final String phone;

    /** 좌표를 다시 확정하는 수정에서만 채웁니다. 채우면 두 값이 함께 SET에 들어갑니다. */
    private final BigDecimal latitude;
    private final BigDecimal longitude;

    /** 좌표 재확정의 근거가 된 저장 주소. 좌표를 바꾸지 않는 수정에서는 {@code null}입니다. */
    private final String expectedRoadAddress;

    public boolean isCoordinatesProvided() {
        return latitude != null && longitude != null;
    }
}
