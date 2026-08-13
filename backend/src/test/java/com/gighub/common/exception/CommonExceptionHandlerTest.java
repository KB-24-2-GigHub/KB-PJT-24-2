package com.gighub.common.exception;

import com.gighub.bank.exception.BankAccountForbiddenException;
import com.gighub.bank.exception.InsufficientBankBalanceException;
import com.gighub.common.api.ApiErrorCode;
import com.gighub.common.trace.TraceIdFilter;
import com.gighub.common.trace.TraceIds;
import com.gighub.document.exception.DocumentNotFoundException;
import com.gighub.wallet.exception.EscrowAccessDeniedException;
import com.gighub.wallet.exception.InvalidFundingRequestException;
import com.gighub.wallet.exception.InvalidIdempotencyKeyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CommonExceptionHandlerTest {

    private static final String INTERNAL_MESSAGE =
            "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ThrowingController())
                .setControllerAdvice(new CommonExceptionHandler())
                .addFilters(new TraceIdFilter())
                .build();
    }

    @Test
    void domainValidationFailuresUseApprovedCode() throws Exception {
        mockMvc.perform(get("/test/invalid-key"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("invalid"))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist());

        mockMvc.perform(get("/test/invalid-funding"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void authenticationAndAuthorizationKeepDifferentStatuses() throws Exception {
        mockMvc.perform(get("/test/auth-required"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get("/test/escrow-forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void domainConflictAndNotFoundUseApprovedCodes() throws Exception {
        mockMvc.perform(get("/test/balance-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        mockMvc.perform(get("/test/document-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void bankAuthorizationFailureDoesNotRevealAccountExistence() {
        String missingMessage = new BankAccountForbiddenException("계좌를 찾을 수 없습니다.")
                .getMessage();
        String otherOwnerMessage = new BankAccountForbiddenException("타인 소유 계좌입니다.")
                .getMessage();

        assertEquals("계좌를 사용할 수 없습니다.", missingMessage);
        assertEquals(missingMessage, otherOwnerMessage);
    }

    @Test
    void beanValidationIncludesOnlyFieldErrorsAsOptionalData() throws Exception {
        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("입력값을 확인해 주세요."))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andExpect(jsonPath("$.fieldErrors[0].reason").value("이름은 필수입니다."))
                .andExpect(jsonPath("$.timestamp").doesNotExist())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.path").doesNotExist());
    }

    @Test
    void malformedInputsAndMissingHeadersUseValidationError() throws Exception {
        mockMvc.perform(get("/test/type-mismatch").param("id", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/test/model-attribute-type-mismatch").param("page", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("page"))
                .andExpect(jsonPath("$.fieldErrors[0].reason")
                        .value("요청 파라미터 형식이 올바르지 않습니다."))
                .andExpect(jsonPath("$.fieldErrors[0].reason", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("NumberFormatException")
                )));

        mockMvc.perform(post("/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/test/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unexpectedFailureUsesSafeMessageAndRequestTraceId() throws Exception {
        MvcResult result = mockMvc.perform(get("/test/unexpected-duplicate"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value(INTERNAL_MESSAGE))
                .andExpect(jsonPath("$.traceId").isString())
                .andExpect(jsonPath("$.fieldErrors").doesNotExist())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("internal SQL detail")
                )))
                .andReturn();

        String traceId = String.valueOf(
                result.getRequest().getAttribute(TraceIds.REQUEST_ATTRIBUTE)
        );
        assertEquals(traceId, JsonTestValues.readString(result, "traceId"));
        UUID.fromString(traceId);
    }

    @Test
    void approvedErrorCatalogAndHandlerDependencyStayBounded() {
        assertEquals(
                Set.of(
                        "VALIDATION_ERROR",
                        "AUTH_REQUIRED",
                        "FORBIDDEN",
                        "ROLE_MISMATCH",
                        "RESOURCE_NOT_FOUND",
                        "CONFLICT",
                        "IDEMPOTENCY_KEY_REUSED",
                        "WORK_CASE_LOCKED",
                        "INVITATION_EXPIRED",
                        "INVITATION_REVOKED",
                        "INVITATION_ALREADY_ACCEPTED",
                        "INVITATION_TERMS_CHANGED",
                        "CONTRACT_RETENTION_REQUIRED",
                        "QR_INVALID",
                        "QR_REVOKED",
                        "WORKPLACE_LOCATION_REQUIRED",
                        "WORKPLACE_ADDRESS_NOT_RESOLVABLE",
                        "WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE",
                        "LOCATION_INVALID",
                        "OUTSIDE_WORKPLACE_RADIUS",
                        "ATTENDANCE_WORK_CASE_NOT_FOUND",
                        "ATTENDANCE_WORK_CASE_AMBIGUOUS",
                        "ATTENDANCE_ALREADY_COMPLETED",
                        "ATTENDANCE_STATE_CONFLICT",
                        "ATTENDANCE_TEMPORARILY_UNAVAILABLE",
                        "SETTLEMENT_ON_HOLD",
                        "SETTLEMENT_NOT_READY",
                        "SETTLEMENT_ALREADY_PROCESSED",
                        "SETTLEMENT_TEMPORARILY_UNAVAILABLE",
                        "INTERNAL_ERROR"
                ),
                Arrays.stream(ApiErrorCode.values())
                        .map(Enum::name)
                        .collect(java.util.stream.Collectors.toSet())
        );

        for (Method method : CommonExceptionHandler.class.getDeclaredMethods()) {
            for (Class<?> parameterType : method.getParameterTypes()) {
                String packageName = parameterType.getPackageName();
                assertFalse(packageName.startsWith("com.gighub.attendance"));
                assertFalse(packageName.startsWith("com.gighub.bank"));
                assertFalse(packageName.startsWith("com.gighub.document"));
                assertFalse(packageName.startsWith("com.gighub.invitation"));
                assertFalse(packageName.startsWith("com.gighub.wallet"));
            }
        }
        assertTrue(ApiException.class.isAssignableFrom(EscrowAccessDeniedException.class));
    }

    @RestController
    private static class ThrowingController {

        @GetMapping("/test/invalid-key")
        public void invalidKey() {
            throw new InvalidIdempotencyKeyException("invalid");
        }

        @GetMapping("/test/invalid-funding")
        public void invalidFunding() {
            throw new InvalidFundingRequestException("invalid");
        }

        @GetMapping("/test/auth-required")
        public void authRequired() {
            throw new AuthRequiredException("auth");
        }

        @GetMapping("/test/escrow-forbidden")
        public void escrowForbidden() {
            throw new EscrowAccessDeniedException("forbidden");
        }

        @GetMapping("/test/balance-conflict")
        public void balanceConflict() {
            throw new InsufficientBankBalanceException("conflict");
        }

        @GetMapping("/test/document-not-found")
        public void documentNotFound() {
            throw new DocumentNotFoundException("not found");
        }

        @PostMapping("/test/validation")
        public void validate(@Valid @RequestBody ValidationInput input) {
        }

        @GetMapping("/test/type-mismatch")
        public void typeMismatch(@RequestParam Long id) {
        }

        @GetMapping("/test/model-attribute-type-mismatch")
        public void modelAttributeTypeMismatch(@Valid @ModelAttribute PageQuery query) {
        }

        @GetMapping("/test/header")
        public void missingHeader(@RequestHeader("Idempotency-Key") String idempotencyKey) {
        }

        @GetMapping("/test/unexpected-duplicate")
        public void unexpectedDuplicate() {
            throw new DuplicateKeyException("internal SQL detail");
        }
    }

    private static class ValidationInput {

        @NotBlank(message = "이름은 필수입니다.")
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }

    private static class PageQuery {

        private int page;

        public int getPage() {
            return page;
        }

        public void setPage(int page) {
            this.page = page;
        }
    }
}
