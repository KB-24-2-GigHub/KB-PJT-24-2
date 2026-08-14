package com.gighub.workplace.dto;

import javax.validation.constraints.AssertTrue;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.gighub.common.validation.PhoneNormalizer;

/**
 * 승인된 사업장 부분 수정 입력과 정규화·검증 계약입니다(SPEC-349-01).
 *
 * <p>수정 가능한 필드는 {@code name}, {@code roadAddress}, {@code detailAddress},
 * {@code phone}뿐입니다. 대표자명·사업자등록번호·좌표·인증 반경은 등록과 같은 이유로 입력
 * 필드가 아니므로 보내면 거절합니다.</p>
 *
 * <p>부분 수정이라 "보내지 않음"과 "빈 값으로 보냄"을 구분해야 합니다. 값만으로는 두 경우가
 * 모두 {@code null}이라 Builder가 Key의 존재 자체를 기록합니다. 이 구분이 없으면
 * {@code detailAddress}를 지우는 요청과 건드리지 않는 요청이 같은 입력이 됩니다.</p>
 */
@JsonDeserialize(builder = WorkplaceUpdateRequest.Builder.class)
public final class WorkplaceUpdateRequest {

    @Size(max = 120, message = "상호명은 120자 이하여야 합니다.")
    private final String name;

    @Size(max = 255, message = "도로명주소는 255자 이하여야 합니다.")
    private final String roadAddress;

    @Size(max = 100, message = "상세주소는 100자 이하여야 합니다.")
    private final String detailAddress;

    @Pattern(regexp = PhoneNormalizer.VALID_PATTERN, message = "전화번호 형식이 올바르지 않습니다.")
    private final String phone;

    private final boolean nameProvided;
    private final boolean roadAddressProvided;
    private final boolean detailAddressProvided;
    private final boolean phoneProvided;

    private WorkplaceUpdateRequest(Builder builder) {
        this.name = builder.name;
        this.roadAddress = builder.roadAddress;
        this.detailAddress = builder.detailAddress;
        this.phone = builder.phone;
        this.nameProvided = builder.nameProvided;
        this.roadAddressProvided = builder.roadAddressProvided;
        this.detailAddressProvided = builder.detailAddressProvided;
        this.phoneProvided = builder.phoneProvided;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getName() {
        return name;
    }

    public String getRoadAddress() {
        return roadAddress;
    }

    public String getDetailAddress() {
        return detailAddress;
    }

    public String getPhone() {
        return phone;
    }

    public boolean isNameProvided() {
        return nameProvided;
    }

    public boolean isRoadAddressProvided() {
        return roadAddressProvided;
    }

    public boolean isDetailAddressProvided() {
        return detailAddressProvided;
    }

    public boolean isPhoneProvided() {
        return phoneProvided;
    }

    /**
     * 바꿀 필드가 하나도 없는 요청을 거절합니다.
     *
     * <p>빈 Body를 성공으로 처리하면 아무것도 저장하지 않은 204가 나가, 호출자는 수정이
     * 반영됐다고 판단합니다.</p>
     */
    @AssertTrue(message = "수정할 필드를 하나 이상 보내야 합니다.")
    public boolean isAnyFieldProvided() {
        return nameProvided || roadAddressProvided || detailAddressProvided || phoneProvided;
    }

    /**
     * 필수 값은 존재할 때 비울 수 없습니다.
     *
     * <p>DB의 빈 문자열 금지 CHECK와 같은 규칙을 요청 단계에서 먼저 적용합니다. 여기서 막지
     * 않으면 저장 시점의 제약 위반이 사용자 안내가 아닌 서버 오류로 나갑니다. {@code phone}은
     * 정규화 후 {@code null}이 되고 {@code @Pattern}이 {@code null}을 통과시키므로 함께
     * 확인합니다.</p>
     */
    @AssertTrue(message = "상호명·도로명주소·전화번호는 빈 값으로 바꿀 수 없습니다.")
    public boolean isRequiredValuePresentWhenProvided() {
        return isPresentIfProvided(nameProvided, name)
                && isPresentIfProvided(roadAddressProvided, roadAddress)
                && isPresentIfProvided(phoneProvided, phone);
    }

    private static boolean isPresentIfProvided(boolean provided, String value) {
        return !provided || (value != null && !value.isEmpty());
    }

    /**
     * 부분 수정 입력을 정규화해 불변 요청으로 만듭니다.
     *
     * <p>{@code withPrefix = ""}가 없으면 Jackson이 {@code withPhone} 형태를 찾다가 값을
     * 채우지 못하므로 반드시 유지합니다.</p>
     */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {

        private String name;
        private String roadAddress;
        private String detailAddress;
        private String phone;

        private boolean nameProvided;
        private boolean roadAddressProvided;
        private boolean detailAddressProvided;
        private boolean phoneProvided;

        private Builder() {
        }

        public Builder name(String name) {
            this.nameProvided = true;
            this.name = WorkplaceNormalizer.normalizeText(name);
            return this;
        }

        public Builder roadAddress(String roadAddress) {
            this.roadAddressProvided = true;
            this.roadAddress = WorkplaceNormalizer.normalizeText(roadAddress);
            return this;
        }

        /** 유일하게 비울 수 있는 필드입니다. 공백만 남는 입력도 삭제 요청으로 다룹니다. */
        public Builder detailAddress(String detailAddress) {
            this.detailAddressProvided = true;
            this.detailAddress = WorkplaceNormalizer.normalizeOptionalText(detailAddress);
            return this;
        }

        public Builder phone(String phone) {
            this.phoneProvided = true;
            this.phone = PhoneNormalizer.normalize(phone);
            return this;
        }

        /** 수정 대상이 아닌 사업장 필드는 조용히 무시하지 않고 요청 오류로 처리합니다. */
        @JsonAnySetter
        public void rejectUnknownField(String fieldName, Object value) {
            throw new IllegalArgumentException("수정할 수 없는 사업장 필드입니다: " + fieldName);
        }

        public WorkplaceUpdateRequest build() {
            return new WorkplaceUpdateRequest(this);
        }
    }
}
