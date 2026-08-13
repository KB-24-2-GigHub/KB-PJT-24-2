package com.gighub.workplace.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.gighub.common.validation.PhoneNormalizer;

/**
 * 승인된 사업장 등록 입력과 정규화·검증 계약입니다.
 *
 * <p>소유자 식별자는 인증 Principal에서만 정하므로 입력 필드로 두지 않습니다. 인증 반경도
 * 사용자가 정할 수 없어 {@code radiusMeters}, {@code radiusM}은 허용되지 않은 필드로
 * 거절합니다.</p>
 *
 * <p>{@code latitude}, {@code longitude}도 같은 이유로 입력 필드가 아닙니다. SPEC-343-01은
 * 좌표의 출처를 서버 주소 변환 하나로 고정하므로, 클라이언트가 보낸 좌표를 받아 두면 저장에
 * 쓰지 않더라도 계약이 좌표를 허용하는 것처럼 보입니다.</p>
 *
 * <p>검증을 통과한 뒤 값이 바뀌지 않도록 불변으로 두고 역직렬화와 정규화는 Builder가
 * 담당합니다. Builder 없이 Jackson이 직접 필드를 채우면 검증 이후 Setter로 값을 바꿀 수
 * 있는 경로가 남습니다.</p>
 */
@JsonDeserialize(builder = WorkplaceCreateRequest.Builder.class)
public final class WorkplaceCreateRequest {

    @NotBlank(message = "사업자등록번호는 필수입니다.")
    @Pattern(regexp = "^[0-9]{10}$", message = "사업자등록번호는 숫자 10자리여야 합니다.")
    private final String businessRegistrationNumber;

    @NotBlank(message = "상호명은 필수입니다.")
    @Size(max = 120, message = "상호명은 120자 이하여야 합니다.")
    private final String name;

    @NotBlank(message = "대표자명은 필수입니다.")
    @Size(max = 100, message = "대표자명은 100자 이하여야 합니다.")
    private final String representativeName;

    @NotBlank(message = "도로명주소는 필수입니다.")
    @Size(max = 255, message = "도로명주소는 255자 이하여야 합니다.")
    private final String roadAddress;

    @Size(max = 100, message = "상세주소는 100자 이하여야 합니다.")
    private final String detailAddress;

    @NotBlank(message = "사업장 전화번호는 필수입니다.")
    @Pattern(regexp = PhoneNormalizer.VALID_PATTERN, message = "전화번호 형식이 올바르지 않습니다.")
    private final String phone;

    private WorkplaceCreateRequest(Builder builder) {
        this.businessRegistrationNumber = builder.businessRegistrationNumber;
        this.name = builder.name;
        this.representativeName = builder.representativeName;
        this.roadAddress = builder.roadAddress;
        this.detailAddress = builder.detailAddress;
        this.phone = builder.phone;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getBusinessRegistrationNumber() {
        return businessRegistrationNumber;
    }

    public String getName() {
        return name;
    }

    public String getRepresentativeName() {
        return representativeName;
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

    /**
     * 승인 입력을 정규화해 불변 요청으로 만듭니다.
     *
     * <p>{@code withPrefix = ""}가 없으면 Jackson이 {@code withPhone} 형태를 찾다가 값을
     * 채우지 못하므로 반드시 유지합니다.</p>
     */
    @JsonPOJOBuilder(withPrefix = "")
    public static final class Builder {

        private String businessRegistrationNumber;
        private String name;
        private String representativeName;
        private String roadAddress;
        private String detailAddress;
        private String phone;
        private Builder() {
        }

        /**
         * 사업자등록번호는 trim만 적용하고 구분 문자를 벗기지 않습니다.
         *
         * <p>승인 명세의 정규화 대상 목록에 없는 입력이라 표시 형식을 서버가 받아 주면
         * 명세대로면 거절될 요청이 통과합니다. 화면 형식 해제는 클라이언트 몫입니다.</p>
         */
        public Builder businessRegistrationNumber(String businessRegistrationNumber) {
            this.businessRegistrationNumber =
                    WorkplaceNormalizer.normalizeText(businessRegistrationNumber);
            return this;
        }

        public Builder name(String name) {
            this.name = WorkplaceNormalizer.normalizeText(name);
            return this;
        }

        public Builder representativeName(String representativeName) {
            this.representativeName = WorkplaceNormalizer.normalizeText(representativeName);
            return this;
        }

        public Builder roadAddress(String roadAddress) {
            this.roadAddress = WorkplaceNormalizer.normalizeText(roadAddress);
            return this;
        }

        public Builder detailAddress(String detailAddress) {
            this.detailAddress = WorkplaceNormalizer.normalizeOptionalText(detailAddress);
            return this;
        }

        public Builder phone(String phone) {
            this.phone = PhoneNormalizer.normalize(phone);
            return this;
        }

        /** 명세에 없는 사업장 필드는 조용히 무시하지 않고 요청 오류로 처리합니다. */
        @JsonAnySetter
        public void rejectUnknownField(String fieldName, Object value) {
            throw new IllegalArgumentException("허용되지 않은 사업장 필드입니다: " + fieldName);
        }

        public WorkplaceCreateRequest build() {
            return new WorkplaceCreateRequest(this);
        }
    }
}
