package com.gighub.workplace.dto;

import java.util.Set;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkplaceCreateRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void normalizesApprovedFieldsBeforeValidation() {
        WorkplaceCreateRequest request = validBuilder()
                .businessRegistrationNumber("  1234567890  ")
                .name("  강남점  ")
                .representativeName("  김사장  ")
                .roadAddress("  서울 강남구 테헤란로 1  ")
                .phone("02-1234 5678")
                .build();

        assertTrue(validator.validate(request).isEmpty());
        assertEquals("1234567890", request.getBusinessRegistrationNumber());
        assertEquals("강남점", request.getName());
        assertEquals("김사장", request.getRepresentativeName());
        assertEquals("서울 강남구 테헤란로 1", request.getRoadAddress());
        assertEquals("0212345678", request.getPhone());
    }

    @Test
    void acceptsRequestWithoutOptionalFields() {
        WorkplaceCreateRequest request = validBuilder()
                .detailAddress(null)
                .build();

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void treatsBlankDetailAddressAsAbsent() {
        WorkplaceCreateRequest request = validBuilder().detailAddress("   ").build();

        assertTrue(validator.validate(request).isEmpty());
        assertNull(request.getDetailAddress());
    }

    @Test
    void rejectsBusinessRegistrationNumberThatIsNotTenDigits() {
        assertTrue(hasViolation(
                validate(validBuilder().businessRegistrationNumber("123456789")),
                "businessRegistrationNumber"));
        assertTrue(hasViolation(
                validate(validBuilder().businessRegistrationNumber("12345678901")),
                "businessRegistrationNumber"));

        assertTrue(hasViolation(
                validate(validBuilder().businessRegistrationNumber("123-45-6789A")),
                "businessRegistrationNumber"));

        WorkplaceCreateRequest blank = validBuilder().businessRegistrationNumber("   ").build();
        assertTrue(hasViolation(validator.validate(blank), "businessRegistrationNumber"));
    }

    /**
     * 사업자등록번호는 승인 명세의 정규화 대상 목록에 없습니다.
     *
     * <p>서버가 표시 형식을 벗겨 주면 명세대로면 400인 요청이 통과해 입력 계약이 넓어지므로
     * 화면 형식은 거절하고 입력값을 그대로 둡니다.</p>
     */
    @Test
    void rejectsBusinessRegistrationNumberInDisplayFormat() {
        WorkplaceCreateRequest request = validBuilder()
                .businessRegistrationNumber("123-45-67890")
                .build();

        assertTrue(hasViolation(validator.validate(request), "businessRegistrationNumber"));
        assertEquals("123-45-67890", request.getBusinessRegistrationNumber());
    }

    /** 앞뒤 공백은 사용자의 입력 실수라 trim만 적용하고 그 뒤에는 숫자 10자리를 그대로 요구합니다. */
    @Test
    void acceptsBusinessRegistrationNumberWithSurroundingWhitespaceOnly() {
        WorkplaceCreateRequest request = validBuilder()
                .businessRegistrationNumber("  1234567890  ")
                .build();

        assertTrue(validator.validate(request).isEmpty());
        assertEquals("1234567890", request.getBusinessRegistrationNumber());
    }

    @Test
    void acceptsTextValuesAtDatabaseMaximumLengths() {
        WorkplaceCreateRequest request = validBuilder()
                .name("가".repeat(120))
                .representativeName("나".repeat(100))
                .roadAddress("다".repeat(255))
                .detailAddress("라".repeat(100))
                .build();

        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void rejectsTextValuesPastDatabaseLengths() {
        Set<ConstraintViolation<WorkplaceCreateRequest>> violations = validate(validBuilder()
                .name("가".repeat(121))
                .representativeName("나".repeat(101))
                .roadAddress("다".repeat(256))
                .detailAddress("라".repeat(101)));

        assertTrue(hasViolation(violations, "name"));
        assertTrue(hasViolation(violations, "representativeName"));
        assertTrue(hasViolation(violations, "roadAddress"));
        assertTrue(hasViolation(violations, "detailAddress"));
    }

    @Test
    void requiresApprovedPhoneFormat() {
        assertTrue(hasViolation(validate(validBuilder().phone("123-456-789")), "phone"));

        // 사업장 전화번호는 선택 입력이 아니므로 공백만 보내면 누락으로 처리합니다.
        WorkplaceCreateRequest blank = validBuilder().phone(" - ").build();
        assertTrue(hasViolation(validator.validate(blank), "phone"));
        assertNull(blank.getPhone());
    }

    @Test
    void rejectsFieldsOutsideApprovedContract() {
        WorkplaceCreateRequest.Builder builder = validBuilder();

        // 인증 반경과 소유자는 사용자가 정할 수 없는 값이라 필드 자체를 거절합니다.
        assertThrows(IllegalArgumentException.class, () -> builder.rejectUnknownField("radiusM", 500));
        assertThrows(IllegalArgumentException.class, () -> builder.rejectUnknownField("radiusMeters", 500));
        assertThrows(IllegalArgumentException.class, () -> builder.rejectUnknownField("ownerUserId", 999));

        // 좌표는 서버가 주소로 확정하므로 요청 필드로 받지 않습니다(SPEC-343-01).
        assertThrows(IllegalArgumentException.class,
                () -> builder.rejectUnknownField("latitude", 37.1234567));
        assertThrows(IllegalArgumentException.class,
                () -> builder.rejectUnknownField("longitude", 127.1234567));
    }

    private WorkplaceCreateRequest.Builder validBuilder() {
        return WorkplaceCreateRequest.builder()
                .businessRegistrationNumber("1234567890")
                .name("강남점")
                .representativeName("김사장")
                .roadAddress("서울 강남구 테헤란로 1")
                .detailAddress("2층")
                .phone("0212345678");
    }

    private Set<ConstraintViolation<WorkplaceCreateRequest>> validate(
            WorkplaceCreateRequest.Builder builder) {
        return validator.validate(builder.build());
    }

    private boolean hasViolation(
            Set<ConstraintViolation<WorkplaceCreateRequest>> violations,
            String field) {
        return violations.stream()
                .anyMatch(violation -> violation.getPropertyPath().toString().equals(field));
    }
}
