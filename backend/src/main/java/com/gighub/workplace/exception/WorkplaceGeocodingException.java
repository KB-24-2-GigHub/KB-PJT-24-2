package com.gighub.workplace.exception;

import com.gighub.common.api.ApiErrorCode;
import com.gighub.common.exception.ApiException;

import org.springframework.http.HttpStatus;

/**
 * 주소 변환 실패를 SPEC-343-01의 승인된 오류 계약으로 반환합니다.
 *
 * <p>주소 자체의 문제와 외부 서비스 장애는 사용자가 해야 할 행동이 다릅니다. 전자는 주소를
 * 고쳐 다시 시도해야 하고 후자는 같은 요청을 잠시 뒤 다시 보내면 됩니다. 두 상황을 하나의
 * 실패로 합치면 화면이 그 구분을 만들 수 없어 상태와 Code를 함께 고정한 정적 Factory로만
 * 만들 수 있게 합니다.</p>
 */
public final class WorkplaceGeocodingException extends ApiException {

    private WorkplaceGeocodingException(HttpStatus status, ApiErrorCode code, String message) {
        super(status, code, message);
    }

    /**
     * 주소를 좌표로 바꿀 수 없거나 후보가 여러 건이라 하나로 확정할 수 없습니다.
     *
     * <p>사용자가 주소를 고쳐야 하는 확정 실패이므로 재시도를 안내하지 않습니다.</p>
     */
    public static WorkplaceGeocodingException addressNotResolvable() {
        return new WorkplaceGeocodingException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ApiErrorCode.WORKPLACE_ADDRESS_NOT_RESOLVABLE,
                "주소로 사업장 위치를 확인할 수 없습니다. 도로명주소를 다시 확인해 주세요.");
    }

    /**
     * 외부 주소 변환 서비스가 Timeout·오류·인증 실패로 응답하지 못했습니다.
     *
     * <p>외부 서비스의 상태 코드와 응답 본문은 옮기지 않습니다. 그대로 노출하면 내부 연동
     * 구성과 키 상태가 밖으로 드러납니다.</p>
     */
    public static WorkplaceGeocodingException temporarilyUnavailable() {
        return new WorkplaceGeocodingException(
                HttpStatus.SERVICE_UNAVAILABLE,
                ApiErrorCode.WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE,
                "지금은 사업장 위치를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }
}
