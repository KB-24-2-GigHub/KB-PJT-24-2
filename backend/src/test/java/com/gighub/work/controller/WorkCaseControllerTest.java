package com.gighub.work.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.common.exception.WorkCaseLockedException;
import com.gighub.config.ApiJsonMapper;
import com.gighub.member.domain.UserRole;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.work.dto.WorkCaseDetailResponse;
import com.gighub.work.dto.WorkCaseSummaryResponse;
import com.gighub.work.service.WorkCaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class WorkCaseControllerTest {

    private WorkCaseService workCaseService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        workCaseService = mock(WorkCaseService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new WorkCaseController(workCaseService))
                .setControllerAdvice(new CommonExceptionHandler())
                .setMessageConverters(
                        new MappingJackson2HttpMessageConverter(ApiJsonMapper.create()))
                .build();
    }

    // ---------- POST ----------

    @Test
    void createReturnsCreatedWorkCaseIdentifier() throws Exception {
        when(workCaseService.create(any(), any())).thenReturn(101L);

        mockMvc.perform(post("/api/workplaces/5/work-cases")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.workCaseId").value(101));
    }

    @Test
    void createRejectsRequestWithoutAuthentication() throws Exception {
        mockMvc.perform(post("/api/workplaces/5/work-cases")
                        .contentType(APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createRejectsMissingRequiredField() throws Exception {
        mockMvc.perform(post("/api/workplaces/5/work-cases")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content("{"
                                + "\"workDate\":\"2026-08-10\","
                                + "\"startTime\":\"09:00\","
                                + "\"endTime\":\"18:00\","
                                + "\"breakMinutes\":60,"
                                + "\"breakPaid\":false,"
                                + "\"dailyWage\":120000"
                                + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createRejectsUnknownField() throws Exception {
        mockMvc.perform(post("/api/workplaces/5/work-cases")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content("{"
                                + "\"title\":\"주말 홀 서빙\","
                                + "\"workDate\":\"2026-08-10\","
                                + "\"startTime\":\"09:00\","
                                + "\"endTime\":\"18:00\","
                                + "\"breakMinutes\":60,"
                                + "\"breakPaid\":false,"
                                + "\"dailyWage\":120000,"
                                + "\"status\":\"ACCEPTED\""
                                + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createSurfacesRoleRejectionAsRoleMismatch() throws Exception {
        when(workCaseService.create(any(), any()))
                .thenThrow(new RoleMismatchException("근무 Case는 OWNER만 관리할 수 있습니다."));

        mockMvc.perform(post("/api/workplaces/5/work-cases")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
    }

    @Test
    void createSurfacesUnownedWorkplaceAsNotFound() throws Exception {
        when(workCaseService.create(any(), any()))
                .thenThrow(new ResourceNotFoundException("등록할 수 있는 사업장을 찾을 수 없습니다."));

        mockMvc.perform(post("/api/workplaces/5/work-cases")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // ---------- PATCH ----------

    @Test
    void updateReturnsNoContentOnSuccess() throws Exception {
        mockMvc.perform(patch("/api/work-cases/101")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isNoContent());
    }

    @Test
    void updateSurfacesNonDraftAsWorkCaseLocked() throws Exception {
        doThrow(new WorkCaseLockedException("DRAFT 상태의 근무만 처리할 수 있습니다."))
                .when(workCaseService).update(any(), any());

        mockMvc.perform(patch("/api/work-cases/101")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_CASE_LOCKED"));
    }

    @Test
    void updateSurfacesUnownedWorkCaseAsNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("근무 Case를 찾을 수 없습니다."))
                .when(workCaseService).update(any(), any());

        mockMvc.perform(patch("/api/work-cases/101")
                        .principal(ownerAuthentication())
                        .contentType(APPLICATION_JSON)
                        .content(validBody()))
                .andExpect(status().isNotFound());
    }

    // ---------- DELETE ----------

    @Test
    void deleteReturnsNoContentOnSuccess() throws Exception {
        mockMvc.perform(delete("/api/work-cases/101").principal(ownerAuthentication()))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteSurfacesNonDraftAsWorkCaseLocked() throws Exception {
        doThrow(new WorkCaseLockedException("DRAFT 상태의 근무만 처리할 수 있습니다."))
                .when(workCaseService).delete(any(), any());

        mockMvc.perform(delete("/api/work-cases/101").principal(ownerAuthentication()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORK_CASE_LOCKED"));
    }

    @Test
    void deleteRejectsRequestWithoutAuthentication() throws Exception {
        mockMvc.perform(delete("/api/work-cases/101"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- GET summary ----------

    @Test
    void summaryReturnsApprovedEightBucketEnvelope() throws Exception {
        when(workCaseService.summary(any(), anyLong())).thenReturn(WorkCaseSummaryResponse.builder()
                .draft(2).accepted(1).ready(3).inProgress(1)
                .checkOutMissing(0).completed(8).noShow(1).canceled(2)
                .build());

        mockMvc.perform(get("/api/workplaces/5/work-cases/summary")
                        .principal(ownerAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.draft").value(2))
                .andExpect(jsonPath("$.data.inProgress").value(1))
                .andExpect(jsonPath("$.data.checkOutMissing").value(0))
                .andExpect(jsonPath("$.data.noShow").value(1));
    }

    @Test
    void summarySurfacesUnownedWorkplaceAsNotFound() throws Exception {
        when(workCaseService.summary(any(), anyLong()))
                .thenThrow(new ResourceNotFoundException("사업장을 찾을 수 없습니다."));

        mockMvc.perform(get("/api/workplaces/5/work-cases/summary")
                        .principal(ownerAuthentication()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void summaryRejectsRequestWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/workplaces/5/work-cases/summary"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- GET list ----------

    @Test
    void listReturnsApprovedPageEnvelope() throws Exception {
        when(workCaseService.list(any(), anyLong(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/workplaces/5/work-cases")
                        .principal(ownerAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.page.number").value(0))
                .andExpect(jsonPath("$.data.page.size").value(20));
    }

    @Test
    void listPassesQueryParamsToService() throws Exception {
        when(workCaseService.list(any(), anyLong(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0));

        mockMvc.perform(get("/api/workplaces/5/work-cases")
                        .param("keyword", "서빙")
                        .param("status", "DRAFT")
                        .param("from", "2026-08-01")
                        .param("to", "2026-08-31")
                        .param("page", "0")
                        .param("size", "20")
                        .principal(ownerAuthentication()))
                .andExpect(status().isOk());

        verify(workCaseService).list(
                any(), eq(5L), eq("서빙"), eq(WorkCaseStatus.DRAFT),
                eq(LocalDate.of(2026, 8, 1)), eq(LocalDate.of(2026, 8, 31)), eq(0), eq(20));
    }

    @Test
    void listSurfacesUnownedWorkplaceAsNotFound() throws Exception {
        when(workCaseService.list(any(), anyLong(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new ResourceNotFoundException("사업장을 찾을 수 없습니다."));

        mockMvc.perform(get("/api/workplaces/5/work-cases").principal(ownerAuthentication()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void listRejectsRequestWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/workplaces/5/work-cases"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- GET detail ----------

    @Test
    void detailReturnsApprovedEnvelope() throws Exception {
        when(workCaseService.detail(any(), anyLong())).thenReturn(WorkCaseDetailResponse.of(
                101L,
                "Weekend shift",
                LocalDateTime.of(2026, 8, 20, 9, 0),
                LocalDateTime.of(2026, 8, 20, 18, 0),
                60,
                false,
                120_000L,
                WorkCaseStatus.DRAFT,
                1,
                "Gangnam",
                "Seoul",
                null,
                null,
                null,
                WorkCaseDetailResponse.AttendanceSummary.of(null, null),
                null,
                WorkCaseDetailResponse.SettlementSummary.of(
                        "COMPLETED",
                        120_000L,
                        90_000L,
                        30_000L,
                        420L,
                        105L,
                        0L,
                        "CHECKED_OUT",
                        "ATTENDANCE_V1",
                        LocalDateTime.of(2026, 8, 20, 18, 5),
                        null,
                        LocalDateTime.of(2026, 8, 20, 18, 6))));

        mockMvc.perform(get("/api/work-cases/101").principal(ownerAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.workCaseId").value(101))
                .andExpect(jsonPath("$.data.worker").doesNotExist())
                .andExpect(jsonPath("$.data.attendance").exists())
                .andExpect(jsonPath("$.data.attendance.checkedInAt").doesNotExist())
                .andExpect(jsonPath("$.data.settlement.originalEscrowAmount").value(120_000))
                .andExpect(jsonPath("$.data.settlement.workerPaidAmount").value(90_000))
                .andExpect(jsonPath("$.data.settlement.ownerRefundAmount").value(30_000))
                .andExpect(jsonPath("$.data.settlement.deductionAmount").value(30_000))
                .andExpect(jsonPath("$.data.settlement.deductionBaseMinutes").value(420))
                .andExpect(jsonPath("$.data.settlement.lateMinutes").value(105))
                .andExpect(jsonPath("$.data.settlement.earlyLeaveMinutes").value(0))
                .andExpect(jsonPath("$.data.settlement.calculationReason").value("CHECKED_OUT"))
                .andExpect(jsonPath("$.data.settlement.calculationVersion")
                        .value("ATTENDANCE_V1"))
                .andExpect(jsonPath("$.data.settlement.calculatedAt")
                        .value("2026-08-20T09:05:00Z"));
    }

    @Test
    void detailSurfacesMissingWorkCaseAsNotFound() throws Exception {
        when(workCaseService.detail(any(), anyLong()))
                .thenThrow(new ResourceNotFoundException("근무 Case를 찾을 수 없습니다."));

        mockMvc.perform(get("/api/work-cases/101").principal(ownerAuthentication()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void detailRejectsRequestWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/work-cases/101"))
                .andExpect(status().isUnauthorized());
    }

    private Authentication ownerAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(7L, UserRole.OWNER, "김사장"), null, List.of());
    }

    private String validBody() {
        return "{"
                + "\"title\":\"주말 홀 서빙\","
                + "\"workDate\":\"2026-08-10\","
                + "\"startTime\":\"09:00\","
                + "\"endTime\":\"18:00\","
                + "\"breakMinutes\":60,"
                + "\"breakPaid\":false,"
                + "\"dailyWage\":120000"
                + "}";
    }
}
