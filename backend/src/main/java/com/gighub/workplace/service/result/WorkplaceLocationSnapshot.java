package com.gighub.workplace.service.result;

import java.math.BigDecimal;

/**
 * 거리 판정에 필요한 최소 사업장 위치입니다.
 *
 * <p>좌표는 근무 생성 시점 Snapshot이 아니라 현재 {@code workplaces} 행의 값입니다.
 * API_SPEC 6.0.0이 스캔 거리 기준을 현재 사업장 좌표로 고정했습니다.</p>
 *
 * <p>좌표가 없는 사업장은 두 값이 모두 {@code null}입니다. 컬럼 제약이 둘을 함께
 * 채우거나 함께 비우도록 강제하므로 한쪽만 있는 상태는 없습니다.</p>
 *
 * <p>{@code workplaceId}는 원시 {@code long}이 아니라 {@code Long}입니다. MyBatis가
 * {@code <constructor>} 매핑에서 리플렉션으로 생성자를 찾을 때 XML의 {@code javaType}과
 * 정확히 일치하는 참조 타입을 요구하며, 원시 타입 생성자는 찾지 못해
 * {@code ReflectionException}으로 실패합니다.</p>
 */
public record WorkplaceLocationSnapshot(
        Long workplaceId,
        BigDecimal latitude,
        BigDecimal longitude) {

    public boolean hasCoordinates() {
        return latitude != null && longitude != null;
    }
}
