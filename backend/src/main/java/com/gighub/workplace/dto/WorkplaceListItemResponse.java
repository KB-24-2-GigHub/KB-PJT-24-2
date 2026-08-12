package com.gighub.workplace.dto;

import java.math.BigDecimal;

import lombok.Getter;

/**
 * OWNER 사업장 목록의 Item 하나입니다.
 *
 * <p>승인 명세가 고정한 아홉 필드만 두고, 목록에서 제외된 {@code latitude}·{@code longitude}는
 * 조회 행에도 응답에도 없습니다.</p>
 */
@Getter
public final class WorkplaceListItemResponse {

    private final Long workplaceId;
    private final String businessRegistrationNumber;
    private final String name;
    private final String representativeName;
    private final String roadAddress;
    private final String detailAddress;
    private final String phone;
    private final int radiusMeters;
    private final String status;

    private WorkplaceListItemResponse(
            Long workplaceId,
            String businessRegistrationNumber,
            String name,
            String representativeName,
            String roadAddress,
            String detailAddress,
            String phone,
            BigDecimal radiusMeters,
            String status) {
        this.workplaceId = workplaceId;
        this.businessRegistrationNumber = businessRegistrationNumber;
        this.name = name;
        this.representativeName = representativeName;
        this.roadAddress = roadAddress;
        this.detailAddress = detailAddress;
        this.phone = phone;
        // 명세의 반경은 정수 100입니다. DECIMAL(8,2)를 그대로 직렬화하면 100.00이 나가므로
        // 저장 정밀도를 응답 계약으로 흘리지 않고 여기서 정수로 맞춥니다.
        this.radiusMeters = radiusMeters.intValue();
        this.status = status;
    }

    public static WorkplaceListItemResponse of(
            Long workplaceId,
            String businessRegistrationNumber,
            String name,
            String representativeName,
            String roadAddress,
            String detailAddress,
            String phone,
            BigDecimal radiusMeters,
            String status) {
        return new WorkplaceListItemResponse(
                workplaceId,
                businessRegistrationNumber,
                name,
                representativeName,
                roadAddress,
                detailAddress,
                phone,
                radiusMeters,
                status);
    }
}
