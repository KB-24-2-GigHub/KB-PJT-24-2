package com.gighub.workplace.service.command;

import lombok.Builder;
import lombok.Getter;

/**
 * 검증을 통과한 사업장 등록 입력을 Service로 전달하는 불변 Command입니다.
 *
 * <p>소유자와 인증 반경, 최초 상태는 입력이 아니므로 필드로 두지 않습니다. 소유자는
 * Service가 인증 Principal에서 채우고 반경·상태는 Mapper XML이 계약값으로 기록합니다.</p>
 *
 * <p>좌표도 입력이 아닙니다. Service가 도로명주소를 변환해 확정합니다(SPEC-343-01).</p>
 */
@Getter
@Builder
public final class WorkplaceCreateCommand {

    private final String businessRegistrationNumber;
    private final String name;
    private final String representativeName;
    private final String roadAddress;
    private final String detailAddress;
    private final String phone;
}
