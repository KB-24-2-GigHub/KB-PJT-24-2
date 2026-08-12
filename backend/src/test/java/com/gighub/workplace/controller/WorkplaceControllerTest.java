package com.gighub.workplace.controller;

import java.math.BigDecimal;
import java.util.List;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.common.exception.ConflictException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.ValidationException;
import com.gighub.member.domain.UserRole;
import com.gighub.workplace.dto.WorkplaceListItemResponse;
import com.gighub.workplace.service.WorkplaceService;
import com.gighub.workplace.service.command.WorkplaceCreateCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
        assertEquals(0, new BigDecimal("37.1234567").compareTo(command.getLatitude()));
        assertEquals(0, new BigDecimal("127.1234567").compareTo(command.getLongitude()));
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
        assertNull(command.getLatitude());
        assertNull(command.getLongitude());
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

    @Test
    void createReportsMissingCoordinateAsFieldError() throws Exception {
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
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("longitude"));

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
                + "\"phone\":\"02-1234-5678\","
                + "\"latitude\":37.1234567,"
                + "\"longitude\":127.1234567"
                + "}";
    }
}
