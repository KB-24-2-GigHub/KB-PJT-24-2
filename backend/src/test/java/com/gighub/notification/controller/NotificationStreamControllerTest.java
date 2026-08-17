package com.gighub.notification.controller;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.member.domain.UserRole;
import com.gighub.notification.sse.NotificationEmitterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/**
 * 구독 경계와 프록시 대응 헤더를 고정한다(#386).
 *
 * <p>구독 대상은 Request가 아니라 인증 Principal에서만 나온다. 헤더가 빠지면 로컬에서는 멀쩡하고
 * 배포에서만 이벤트가 도달하지 않으므로, 눈으로 확인되지 않는 것을 테스트로 붙잡는다.</p>
 */
class NotificationStreamControllerTest {

    private static final long WORKER_ID = 22L;
    private static final String PATH = "/api/notifications/stream";

    private NotificationEmitterRegistry emitterRegistry;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() throws Exception {
        emitterRegistry = mock(NotificationEmitterRegistry.class);
        // 실제 subscribe와 같이 최초 한 줄을 미리 넣는다. 응답 헤더는 첫 쓰기에서 확정되므로,
        // 아무것도 보내지 않는 Emitter로는 헤더 자체를 검증할 수 없다.
        SseEmitter emitter = new SseEmitter(1_000L);
        emitter.send(SseEmitter.event().comment("connected"));
        when(emitterRegistry.subscribe(WORKER_ID)).thenReturn(emitter);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new NotificationStreamController(emitterRegistry))
                .build();
    }

    /** 사용자 ID를 Request에서 받지 않는다. 인증 Principal만이 구독 대상을 정한다. */
    @Test
    void subscribesOnlyTheAuthenticatedUsersStream() throws Exception {
        mockMvc.perform(get(PATH).principal(workerAuthentication()))
                .andExpect(request().asyncStarted());

        verify(emitterRegistry).subscribe(WORKER_ID);
    }

    /** nginx 기본 Buffering을 끄지 않으면 배포 환경에서만 이벤트가 도달하지 않는다(#358). */
    @Test
    void disablesProxyBufferingOnTheResponse() throws Exception {
        MvcResult result = mockMvc.perform(get(PATH).principal(workerAuthentication()))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertEquals("no", result.getResponse().getHeader("X-Accel-Buffering"));
        assertEquals("text/event-stream", result.getResponse().getContentType());
    }

    private static Authentication workerAuthentication() {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(WORKER_ID, UserRole.WORKER, "김근로"),
                "N/A",
                List.of());
    }
}
