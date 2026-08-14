package com.gighub.workplace.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.ValidationException;
import com.gighub.member.domain.UserRole;
import com.gighub.workplace.dto.WorkplaceListItemResponse;
import com.gighub.workplace.exception.WorkplaceCoordinatesAlreadySetException;
import com.gighub.workplace.exception.WorkplaceGeocodingException;
import com.gighub.workplace.service.WorkplaceService;
import com.gighub.workplace.service.command.WorkplaceCoordinateConfirmCommand;
import com.gighub.workplace.service.command.WorkplaceCreateCommand;
import com.gighub.workplace.service.command.WorkplaceUpdateCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkplaceControllerTest {

    private WorkplaceService workplaceService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        workplaceService = mock(WorkplaceService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new WorkplaceController(workplaceService))
                .setControllerAdvice(new CommonExceptionHandler())
                .build();
    }

    @Test
    void createReturnsCreatedWorkplaceIdentifier() throws Exception {
        when(workplaceService.create(any(), any())).thenReturn(42L);

        mockMvc.perform(post("/api/workplaces")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.workplaceId").value(42));
    }

    @Test
    void createPassesAuthenticatedPrincipalAndNormalizedValuesToService() throws Exception {
        AuthPrincipal principal = new AuthPrincipal(7L, UserRole.OWNER, "김사장");
        when(workplaceService.create(any(), any())).thenReturn(42L);

        mockMvc.perform(post("/api/workplaces")
                        .principal(new UsernamePasswordAuthenticationToken(principal, null, List.of()))
                        .contentType(APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated());

        ArgumentCaptor<WorkplaceCreateCommand> captor =
                ArgumentCaptor.forClass(WorkplaceCreateCommand.class);
        verify(workplaceService).create(eq(principal), captor.capture());

        WorkplaceCreateCommand command = captor.getValue();
        assertEquals("1234567890", command.getBusinessRegistrationNumber());
        assertEquals("강남점", command.getName());
        assertEquals("김사장", command.getRepresentativeName());
        assertEquals("서울 강남구 테헤란로 1", command.getRoadAddress());
        assertEquals("2층", command.getDetailAddress());
        assertEquals("0212345678", command.getPhone());
    }

    @Test
    void createKeepsOptionalValuesAbsent() throws Exception {
        when(workplaceService.create(any(), any())).thenReturn(42L);

        mockMvc.perform(post("/api/workplaces")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content("{"
                                + "\"businessRegistrationNumber\":\"1234567890\","
                                + "\"name\":\"강남점\","
                                + "\"representativeName\":\"김사장\","
                                + "\"roadAddress\":\"서울 강남구 테헤란로 1\","
                                + "\"phone\":\"0212345678\""
                                + "}"))
                .andExpect(status().isCreated());

        ArgumentCaptor<WorkplaceCreateCommand> captor =
                ArgumentCaptor.forClass(WorkplaceCreateCommand.class);
        verify(workplaceService).create(any(), captor.capture());

        WorkplaceCreateCommand command = captor.getValue();
        assertNull(command.getDetailAddress());
    }

    @Test
    void createWithoutAuthenticationIsRejectedBeforeService() throws Exception {
        mockMvc.perform(post("/api/workplaces")
                        .contentType(APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verify(workplaceService, never()).create(any(), any());
    }

    /** 반경은 사용자가 정할 수 없는 값이라 Service까지 가지 않고 요청 단계에서 막힙니다. */
    @Test
    void createRejectsUnapprovedRadiusField() throws Exception {
        mockMvc.perform(post("/api/workplaces")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content("{"
                                + "\"businessRegistrationNumber\":\"1234567890\","
                                + "\"name\":\"강남점\","
                                + "\"representativeName\":\"김사장\","
                                + "\"roadAddress\":\"서울 강남구 테헤란로 1\","
                                + "\"phone\":\"0212345678\","
                                + "\"radiusM\":500"
                                + "}"))
                .andExpect(status().isBadRequest())
                // @Valid 실패와 다른 핸들러(handleNotReadable)를 타므로 Code를 함께 고정합니다.
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(workplaceService, never()).create(any(), any());
    }

    /** 좌표는 서버가 주소로 확정하므로 Body에 실리면 저장 이전에 거절합니다(SPEC-343-01). */
    @Test
    void createRejectsClientSuppliedCoordinates() throws Exception {
        mockMvc.perform(post("/api/workplaces")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content("{"
                                + "\"businessRegistrationNumber\":\"1234567890\","
                                + "\"name\":\"강남점\","
                                + "\"representativeName\":\"김사장\","
                                + "\"roadAddress\":\"서울 강남구 테헤란로 1\","
                                + "\"phone\":\"0212345678\","
                                + "\"latitude\":37.1234567"
                                + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(workplaceService, never()).create(any(), any());
    }

    @Test
    void createSurfacesRoleRejectionAsRoleMismatch() throws Exception {
        when(workplaceService.create(any(), any()))
                .thenThrow(new RoleMismatchException("사업장은 OWNER만 등록할 수 있습니다."));

        mockMvc.perform(post("/api/workplaces")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
    }

    @Test
    void createSurfacesDuplicateBusinessNumberAsConflict() throws Exception {
        when(workplaceService.create(any(), any()))
                .thenThrow(new ConflictException("이미 등록된 사업자등록번호입니다."));

        mockMvc.perform(post("/api/workplaces")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void findOwnedReturnsApprovedPageEnvelope() throws Exception {
        when(workplaceService.findOwnedWorkplaces(any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(listItem()), 0, 20, 1));

        mockMvc.perform(get("/api/workplaces").principal(ownerAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].workplaceId").value(11))
                .andExpect(jsonPath("$.data.content[0].businessRegistrationNumber").value("1234567890"))
                .andExpect(jsonPath("$.data.content[0].name").value("강남점"))
                .andExpect(jsonPath("$.data.content[0].representativeName").value("김사장"))
                .andExpect(jsonPath("$.data.content[0].roadAddress").value("서울 강남구 테헤란로 1"))
                .andExpect(jsonPath("$.data.content[0].detailAddress").value("2층"))
                .andExpect(jsonPath("$.data.content[0].phone").value("0212345678"))
                .andExpect(jsonPath("$.data.content[0].radiusMeters").value(100))
                .andExpect(jsonPath("$.data.content[0].attendanceLocationConfirmed").value(true))
                .andExpect(jsonPath("$.data.content[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.size").value(20))
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                .andExpect(jsonPath("$.data.page.totalPages").value(1));
    }

    /** 목록 Item에 좌표가 없다는 계약은 응답 JSON에서 확인해야 회귀를 잡을 수 있습니다. */
    @Test
    void findOwnedOmitsCoordinatesFromListItem() throws Exception {
        when(workplaceService.findOwnedWorkplaces(any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(listItem()), 0, 20, 1));

        mockMvc.perform(get("/api/workplaces").principal(ownerAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].latitude").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].longitude").doesNotExist());
    }

    @Test
    void findOwnedAppliesApprovedPageDefaultsWhenQueryIsAbsent() throws Exception {
        when(workplaceService.findOwnedWorkplaces(any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/workplaces").principal(ownerAuthentication()))
                .andExpect(status().isOk());

        verify(workplaceService).findOwnedWorkplaces(any(), eq(0), eq(20));
    }

    @Test
    void findOwnedPassesRequestedPageAndPrincipalToService() throws Exception {
        AuthPrincipal principal = new AuthPrincipal(7L, UserRole.OWNER, "김사장");
        when(workplaceService.findOwnedWorkplaces(any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 2, 5, 0));

        mockMvc.perform(get("/api/workplaces")
                        .principal(new UsernamePasswordAuthenticationToken(principal, null, List.of()))
                        .param("page", "2")
                        .param("size", "5"))
                .andExpect(status().isOk());

        verify(workplaceService).findOwnedWorkplaces(eq(principal), eq(2), eq(5));
    }

    @Test
    void findOwnedWithoutAuthenticationIsRejectedBeforeService() throws Exception {
        mockMvc.perform(get("/api/workplaces"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verify(workplaceService, never()).findOwnedWorkplaces(any(), anyInt(), anyInt());
    }

    /** 숫자가 아닌 Page Query는 Service까지 가지 않고 승인된 400으로 끊깁니다. */
    @Test
    void findOwnedRejectsNonNumericPageQueryBeforeService() throws Exception {
        mockMvc.perform(get("/api/workplaces")
                        .principal(ownerAuthentication())
                        .param("size", "스무개"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(workplaceService, never()).findOwnedWorkplaces(any(), anyInt(), anyInt());
    }

    @Test
    void findOwnedSurfacesPageBoundaryViolationAsValidationError() throws Exception {
        when(workplaceService.findOwnedWorkplaces(any(), anyInt(), anyInt()))
                .thenThrow(new ValidationException("size는 1 이상 100 이하여야 합니다."));

        mockMvc.perform(get("/api/workplaces")
                        .principal(ownerAuthentication())
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void findOwnedSurfacesRoleRejectionAsRoleMismatch() throws Exception {
        when(workplaceService.findOwnedWorkplaces(any(), anyInt(), anyInt()))
                .thenThrow(new RoleMismatchException("사업장 목록은 OWNER만 조회할 수 있습니다."));

        mockMvc.perform(get("/api/workplaces").principal(ownerAuthentication()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
    }

    /** 성공은 본문 없는 204여야 합니다. 200이나 빈 Envelope가 나가면 계약이 달라집니다. */
    @Test
    void confirmLocationReturnsNoContentAndPassesPathAndBodyToService() throws Exception {
        mockMvc.perform(put("/api/workplaces/11/coordinates")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content(validCoordinateBody()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        ArgumentCaptor<WorkplaceCoordinateConfirmCommand> captor =
                ArgumentCaptor.forClass(WorkplaceCoordinateConfirmCommand.class);
        verify(workplaceService).confirmLocation(any(), eq(11L), captor.capture());

        WorkplaceCoordinateConfirmCommand command = captor.getValue();
        assertEquals(0, new BigDecimal("37.1234567").compareTo(command.getLatitude()));
        assertEquals(0, new BigDecimal("127.1234567").compareTo(command.getLongitude()));
        assertEquals(Instant.parse("2026-08-13T01:00:00Z"), command.getCapturedAt());
    }

    /** 이미 다른 좌표가 확정된 사업장은 승인된 409 Code로 나가야 합니다. */
    @Test
    void confirmLocationSurfacesAlreadySetConflictAsApprovedCode() throws Exception {
        doThrow(new WorkplaceCoordinatesAlreadySetException("이미 다른 현장 위치가 확정된 사업장입니다."))
                .when(workplaceService).confirmLocation(any(), any(), any());

        mockMvc.perform(put("/api/workplaces/11/coordinates")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content(validCoordinateBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKPLACE_COORDINATES_ALREADY_SET"));
    }

    /**
     * 좌표 범위와 미승인 필드는 Service에 닿기 전에 끊겨야 합니다.
     *
     * <p>미승인 필드는 {@code @Valid}가 아니라 역직렬화 단계에서 끊기므로 두 경로를 함께
     * 확인합니다.</p>
     */
    @Test
    void confirmLocationRejectsOutOfRangeAndUnapprovedFieldsBeforeService() throws Exception {
        mockMvc.perform(put("/api/workplaces/11/coordinates")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content("{"
                                + "\"latitude\":90.0000001,"
                                + "\"longitude\":127.1234567,"
                                + "\"accuracyMeters\":18.25,"
                                + "\"capturedAt\":\"2026-08-13T01:00:00Z\""
                                + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(put("/api/workplaces/11/coordinates")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content("{"
                                + "\"latitude\":37.1234567,"
                                + "\"longitude\":127.1234567,"
                                + "\"accuracyMeters\":18.25,"
                                + "\"capturedAt\":\"2026-08-13T01:00:00Z\","
                                + "\"radiusMeters\":500"
                                + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(workplaceService, never()).confirmLocation(any(), any(), any());
    }

    /** 성공은 본문 없는 204이고, 보낸 필드만 존재 표시와 함께 Service로 넘어가야 합니다. */
    @Test
    void updateReturnsNoContentAndPassesOnlyProvidedFieldsToService() throws Exception {
        mockMvc.perform(patch("/api/workplaces/11")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content("{"
                                + "\"roadAddress\":\"  서울 강남구 테헤란로 2  \","
                                + "\"phone\":\"02-1234-5679\""
                                + "}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        ArgumentCaptor<WorkplaceUpdateCommand> captor =
                ArgumentCaptor.forClass(WorkplaceUpdateCommand.class);
        verify(workplaceService).update(any(), eq(11L), captor.capture());

        WorkplaceUpdateCommand command = captor.getValue();
        assertTrue(command.isRoadAddressProvided());
        assertEquals("서울 강남구 테헤란로 2", command.getRoadAddress());
        assertTrue(command.isPhoneProvided());
        assertEquals("0212345679", command.getPhone());
        assertFalse(command.isNameProvided());
        assertFalse(command.isDetailAddressProvided());
    }

    /**
     * 상세주소만 값과 존재 여부를 함께 구분해야 하는 필드입니다.
     *
     * <p>명시적 {@code null}은 삭제 요청이고 생략은 유지 요청이라, 둘이 같은 Command가 되면
     * 지우려는 시도가 조용히 무시됩니다.</p>
     */
    @Test
    void updateDistinguishesExplicitNullDetailAddressFromOmission() throws Exception {
        mockMvc.perform(patch("/api/workplaces/11")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content("{\"detailAddress\":null}"))
                .andExpect(status().isNoContent());

        ArgumentCaptor<WorkplaceUpdateCommand> captor =
                ArgumentCaptor.forClass(WorkplaceUpdateCommand.class);
        verify(workplaceService).update(any(), eq(11L), captor.capture());

        assertTrue(captor.getValue().isDetailAddressProvided());
        assertNull(captor.getValue().getDetailAddress());
    }

    /** 수정할 수 없는 필드는 Service에 닿기 전에 끊겨야 합니다. */
    @Test
    void updateRejectsImmutableFieldsBeforeService() throws Exception {
        mockMvc.perform(patch("/api/workplaces/11")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content("{\"representativeName\":\"박사장\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(patch("/api/workplaces/11")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content("{\"roadAddress\":\"서울 강남구 테헤란로 2\",\"latitude\":37.1234567}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(workplaceService, never()).update(any(), any(), any());
    }

    /** 아무것도 바꾸지 않는 성공을 만들지 않기 위해 빈 요청은 400입니다. */
    @Test
    void updateRejectsRequestWithoutAnyEditableField() throws Exception {
        mockMvc.perform(patch("/api/workplaces/11")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(workplaceService, never()).update(any(), any(), any());
    }

    /** 필수 값은 보낼 수는 있어도 비울 수는 없습니다. DB CHECK 위반을 요청 단계에서 막습니다. */
    @Test
    void updateRejectsBlankRequiredValues() throws Exception {
        mockMvc.perform(patch("/api/workplaces/11")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(patch("/api/workplaces/11")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content("{\"phone\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(workplaceService, never()).update(any(), any(), any());
    }

    /** 두 변환 실패는 화면이 재시도 가능 여부를 구분할 수 있도록 다른 Code로 나가야 합니다. */
    @Test
    void updateSurfacesGeocodingFailuresAsApprovedCodes() throws Exception {
        doThrow(WorkplaceGeocodingException.addressNotResolvable())
                .when(workplaceService).update(any(), any(), any());

        mockMvc.perform(patch("/api/workplaces/11")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content(validUpdateBody()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("WORKPLACE_ADDRESS_NOT_RESOLVABLE"));

        doThrow(WorkplaceGeocodingException.temporarilyUnavailable())
                .when(workplaceService).update(any(), any(), any());

        mockMvc.perform(patch("/api/workplaces/11")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content(validUpdateBody()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code")
                        .value("WORKPLACE_GEOCODING_TEMPORARILY_UNAVAILABLE"));
    }

    @Test
    void updateWithoutAuthenticationIsRejectedBeforeService() throws Exception {
        mockMvc.perform(patch("/api/workplaces/11")
                        .contentType(APPLICATION_JSON)
                        .content(validUpdateBody()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        verify(workplaceService, never()).update(any(), any(), any());
    }

    private String validUpdateBody() {
        return "{\"roadAddress\":\"서울 강남구 테헤란로 2\"}";
    }

    private String validCoordinateBody() {
        return "{"
                + "\"latitude\":37.1234567,"
                + "\"longitude\":127.1234567,"
                + "\"accuracyMeters\":18.25,"
                + "\"capturedAt\":\"2026-08-13T01:00:00Z\""
                + "}";
    }

    private WorkplaceListItemResponse listItem() {
        return WorkplaceListItemResponse.of(
                11L,
                "1234567890",
                "강남점",
                "김사장",
                "서울 강남구 테헤란로 1",
                "2층",
                "0212345678",
                new BigDecimal("100.00"),
                true,
                "ACTIVE");
    }

    private Authentication ownerAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(7L, UserRole.OWNER, "김사장"), null, List.of());
    }

    private String validBody() {
        return "{"
                + "\"businessRegistrationNumber\":\"1234567890\","
                + "\"name\":\"  강남점  \","
                + "\"representativeName\":\"김사장\","
                + "\"roadAddress\":\"서울 강남구 테헤란로 1\","
                + "\"detailAddress\":\"2층\","
                + "\"phone\":\"02-1234-5678\""
                + "}";
    }
}
