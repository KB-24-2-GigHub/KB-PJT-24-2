package com.gighub.workplace.mapper.result;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * OWNER 사업장 목록 조회 SQL의 행 하나입니다.
 *
 * <p>승인 명세가 목록 Item에서 제외한 {@code latitude}, {@code longitude} 원문은 여기에도
 * 두지 않습니다. 조회 단계에서 아예 읽지 않아야 이후 계층이 실수로 좌표를 응답에 넣을 수
 * 없습니다. 두 값이 함께 있는지는 {@code attendanceLocationConfirmed}로만 노출합니다.</p>
 *
 * <p>{@code status}는 DB 값을 그대로 담습니다. 허용 값은 {@code ck_workplaces_status}가
 * {@code ACTIVE}, {@code INACTIVE}, {@code DELETED}로 제한합니다.</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkplaceListRow {

    private Long workplaceId;
    private String businessRegistrationNumber;
    private String name;
    private String representativeName;
    private String roadAddress;
    private String detailAddress;
    private String phone;
    private BigDecimal radiusMeters;
    private boolean attendanceLocationConfirmed;
    private String status;
}
