package com.gighub.work.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.common.exception.RoleMismatchException;
import com.gighub.member.domain.UserRole;
import com.gighub.work.dto.ShareableWorkplaceListItemResponse;
import com.gighub.work.service.WorkerQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/worker/workplaces}가 승인된 다섯 필드만 공유 후보로 노출하는지 검증합니다.
 */
@ExtendWith(MockitoExtension.class)
class WorkerWorkplaceControllerTest {

    private static final Long WORKER_ID = 42L;

    @Mock
    private WorkerQueryService workerQueryService;

    private MockMvc mockMvc;
    private Authentication authentication;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WorkerController(workerQueryService))
                .setControllerAdvice(new CommonExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
        AuthPrincipal principal = new AuthPrincipal(WORKER_ID, UserRole.WORKER, "이알바");
        authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    @Test
    void returnsOnlyTheApprovedShareCandidateFields() throws Exception {
        when(workerQueryService.shareableWorkplaces(any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(
                        List.of(ShareableWorkplaceListItemResponse.of(
                                9L,
                                "강남점",
                                "김대표",
                                LocalDateTime.of(2026, 8, 20, 10, 0),
                                LocalDateTime.of(2026, 8, 20, 18, 0))),
                        0, 20, 1L));

        mockMvc.perform(get("/api/worker/workplaces").principal(authentication))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].workplaceId").value(9))
                .andExpect(jsonPath("$.data.content[0].workplaceName").value("강남점"))
                .andExpect(jsonPath("$.data.content[0].ownerName").value("김대표"))
                .andExpect(jsonPath("$.data.content[0].startsAt").exists())
                .andExpect(jsonPath("$.data.content[0].endsAt").exists())
                .andExpect(jsonPath("$.data.page.totalElements").value(1))
                // 공유 요청은 workplaceId만 받고 서버가 Work Case와 OWNER를 결정하므로,
                // 이 응답이 그 식별자를 미리 내보내면 Client가 되돌려 보내는 경로가 생긴다.
                .andExpect(jsonPath("$.data.content[0].workCaseId").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].ownerUserId").doesNotExist());
    }

    @Test
    void usesTheDefaultPageWindowWhenTheQueryIsOmitted() throws Exception {
        when(workerQueryService.shareableWorkplaces(any(), anyInt(), anyInt()))
                .thenReturn(PageResponse.of(List.of(), 0, 20, 0L));

        mockMvc.perform(get("/api/worker/workplaces").principal(authentication))
                .andExpect(status().isOk());

        verify(workerQueryService).shareableWorkplaces(any(), org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(20));
    }

    @Test
    void rejectsANonWorkerWithRoleMismatch() throws Exception {
        doThrow(new RoleMismatchException("WORKER 본인 조회는 WORKER만 사용할 수 있습니다."))
                .when(workerQueryService).shareableWorkplaces(any(), anyInt(), anyInt());

        mockMvc.perform(get("/api/worker/workplaces").principal(authentication))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ROLE_MISMATCH"));
    }
}
