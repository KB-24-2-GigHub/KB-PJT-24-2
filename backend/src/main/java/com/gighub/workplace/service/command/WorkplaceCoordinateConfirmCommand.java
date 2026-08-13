package com.gighub.workplace.service.command;

import java.math.BigDecimal;
import java.time.Instant;

import lombok.Builder;
import lombok.Getter;

/**
 * 검증을 통과한 현장 위치 확정 입력을 Service로 전달하는 불변 Command입니다.
 *
 * <p>{@code accuracyMeters}는 형식만 검증 대상이고 저장하지 않으므로(API_SPEC) Command에
 * 두지 않습니다. HTTP·JSON 계약과 무관한 값만 이 경계를 넘습니다.</p>
 */
@Getter
@Builder
public final class WorkplaceCoordinateConfirmCommand {

    private final BigDecimal latitude;
    private final BigDecimal longitude;
    private final Instant capturedAt;
}
