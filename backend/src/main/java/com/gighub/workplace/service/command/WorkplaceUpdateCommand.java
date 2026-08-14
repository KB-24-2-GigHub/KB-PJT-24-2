package com.gighub.workplace.service.command;

import lombok.Builder;
import lombok.Getter;

/**
 * 검증을 통과한 사업장 부분 수정 입력을 Service로 전달하는 불변 Command입니다(SPEC-349-01).
 *
 * <p>값과 함께 "요청에 그 필드가 있었는지"를 담습니다. 값만으로는 상세주소를 지우는 요청과
 * 건드리지 않는 요청을 구분할 수 없기 때문입니다.</p>
 *
 * <p>좌표는 여기에도 없습니다. 도로명주소가 바뀌면 Service가 새 주소를 변환해 확정합니다.</p>
 */
@Getter
@Builder
public final class WorkplaceUpdateCommand {

    private final boolean nameProvided;
    private final String name;

    private final boolean roadAddressProvided;
    private final String roadAddress;

    private final boolean detailAddressProvided;
    private final String detailAddress;

    private final boolean phoneProvided;
    private final String phone;

    /**
     * 저장된 도로명주소를 기준으로 좌표를 다시 확정해야 하는지 판단합니다.
     *
     * <p>같은 주소를 다시 보낸 요청까지 변환을 호출하면, 상호만 바꾸는 수정도 외부 서비스
     * 호출과 그 실패 가능성을 떠안습니다.</p>
     *
     * @param storedRoadAddress 현재 저장된 도로명주소
     */
    public boolean changesRoadAddressFrom(String storedRoadAddress) {
        return roadAddressProvided && !roadAddress.equals(storedRoadAddress);
    }
}
