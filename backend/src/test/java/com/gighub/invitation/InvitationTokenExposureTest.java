package com.gighub.invitation;

import com.gighub.badge.service.BadgeApplicationService;
import com.gighub.badge.service.result.BadgeCalculationResult;
import com.gighub.invitation.domain.InvitationStatus;
import com.gighub.work.domain.WorkCaseStatus;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.CommonExceptionHandler;
import com.gighub.common.trace.TraceIdFilter;
import com.gighub.config.ApiJsonMapper;
import com.gighub.invitation.config.InvitationLinkFactory;
import com.gighub.invitation.config.InvitationProperties;
import com.gighub.invitation.controller.InvitationController;
import com.gighub.invitation.mapper.InvitationMapper;
import com.gighub.invitation.mapper.InvitationMapperTestDouble;
import com.gighub.invitation.mapper.result.InvitationRow;
import com.gighub.invitation.mapper.result.InvitationWorkCaseRow;
import com.gighub.invitation.service.InvitationQueryService;
import com.gighub.invitation.service.impl.InvitationQueryServiceImpl;
import com.gighub.invitation.token.InvitationTokenCodec;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Token 원문이 응답과 서버 출력 어디에도 남지 않는지 확인하는 회귀 검사입니다.
 *
 * <p>초대 Link는 그 자체가 인증 수단이라 한 번이라도 로그나 오류 Body에 남으면 저장소에
 * Hash만 보관한 의미가 사라집니다. 개별 계층 테스트와 별도로 흐름 전체를 한 번 더
 * 확인합니다.</p>
 *
 * <p>애플리케이션 로그는 두 가지로 막습니다. 초대 패키지에 Logger를 두지 않아 로그 문장이
 * 생길 경로를 없애고, 공통 처리기가 기록하는 예외 메시지에 Token이 섞이지 않는지 확인합니다.
 * 요청 URI 자체를 기록하는 Tomcat Access Log는 애플리케이션 밖의 배포 설정이라 이 검사
 * 범위에 없습니다.</p>
 */
class InvitationTokenExposureTest {

    private static final long INVITATION_ID = 41L;
    private static final LocalDateTime STARTS_AT = LocalDateTime.of(2026, 8, 20, 10, 0);

    private final InvitationTokenCodec codec = new InvitationTokenCodec(
            InvitationProperties.of(
                    "exposure-test-invitation-secret-0123456789", null, "http://localhost:5173")
    );
    private final String token = codec.deriveToken(INVITATION_ID);

    @Test
    void invitationClassesDeclareNoLoggerAtAll() throws Exception {
        Path classes = Paths.get("build", "classes", "java", "main", "com", "gighub", "invitation");
        assertTrue(Files.isDirectory(classes), "컴파일된 초대 Class를 찾을 수 있어야 합니다.");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(classes)) {
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".class"))
                    .toList()) {
                Class<?> type = Class.forName(toClassName(path));
                for (Field field : type.getDeclaredFields()) {
                    String fieldType = field.getType().getName();
                    if (fieldType.endsWith("Logger")) {
                        offenders.add(type.getName() + "." + field.getName());
                    }
                }
            }
        }

        // Logger를 두지 않으면 Token이 실린 로그 문장이 생길 경로 자체가 없습니다.
        assertTrue(offenders.isEmpty(), "초대 패키지에 Logger가 있으면 안 됩니다: " + offenders);
    }

    @Test
    void noResponseBodyOnAnyPathContainsTheToken() throws Exception {
        StubInvitationMapper mapper = new StubInvitationMapper();
        mapper.workCase = draftWorkCase();
        MockMvc mockMvc = mockMvc(mapper);

        List<String> bodies = new ArrayList<>();

        // 정상 조회, 만료, 미존재, 역할 불일치, 그리고 예상하지 못한 실패까지 한 번씩 지납니다.
        mapper.invitation = invitation("PENDING");
        bodies.add(perform(mockMvc, worker()));

        mapper.invitation = invitation("EXPIRED");
        bodies.add(perform(mockMvc, worker()));

        mapper.invitation = null;
        bodies.add(perform(mockMvc, worker()));

        mapper.invitation = invitation("PENDING");
        bodies.add(perform(mockMvc, owner()));

        mapper.failUnexpectedly = true;
        bodies.add(perform(mockMvc, worker()));

        for (String body : bodies) {
            assertFalse(body.contains(token), "응답 Body에 Token 원문이 남으면 안 됩니다.");
        }
    }

    /**
     * 예상하지 못한 실패의 로그 문장에 Token이 실릴 수 있는 입력이 없어야 합니다.
     *
     * <p>공통 처리기는 {@code traceId}와 예외만 기록하므로, 초대 흐름에서 나오는 예외
     * 메시지에 Token이 없으면 로그에도 남지 않습니다.</p>
     */
    @Test
    void failuresRaisedByTheInvitationFlowCarryNoTokenInTheirMessage() {
        StubInvitationMapper mapper = new StubInvitationMapper();
        mapper.workCase = draftWorkCase();
        InvitationQueryService service =
                new InvitationQueryServiceImpl(mapper, codec, noOwnerBadge());

        List<RuntimeException> failures = new ArrayList<>();
        mapper.invitation = null;
        failures.add(captureFailure(service, worker()));
        mapper.invitation = invitation("REVOKED");
        failures.add(captureFailure(service, worker()));
        mapper.invitation = invitation("EXPIRED");
        failures.add(captureFailure(service, worker()));
        mapper.invitation = invitation("PENDING");
        failures.add(captureFailure(service, owner()));
        mapper.failUnexpectedly = true;
        failures.add(captureFailure(service, worker()));

        for (RuntimeException failure : failures) {
            assertFalse(
                    String.valueOf(failure.getMessage()).contains(token),
                    failure.getClass().getSimpleName() + " 메시지에 Token 원문이 남으면 안 됩니다."
            );
        }
    }

    private RuntimeException captureFailure(
            InvitationQueryService service,
            Authentication authentication) {
        try {
            service.findByToken((AuthPrincipal) authentication.getPrincipal(), token);
            throw new AssertionError("실패 경로가 예외 없이 끝났습니다.");
        } catch (RuntimeException expected) {
            return expected;
        }
    }

    @Test
    void invitationUrlIsTheOnlyPlaceTheRawTokenAppears() {
        InvitationLinkFactory linkFactory = new InvitationLinkFactory(
                InvitationProperties.of(
                        "exposure-test-invitation-secret-0123456789",
                        null,
                        "https://app.example.com")
        );

        // 발급 응답의 Link에는 당연히 원문이 들어갑니다. 그 밖의 표현에는 남지 않아야 합니다.
        assertTrue(linkFactory.toInviteUrl(token).endsWith("/invitations/" + token));
        assertFalse(invitation("PENDING").toString().contains(token));
        assertFalse(codec.toString().contains(token));
    }

    private String perform(MockMvc mockMvc, Authentication authentication) throws Exception {
        return mockMvc.perform(get("/api/invitations/{token}", token).principal(authentication))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private MockMvc mockMvc(InvitationMapper mapper) {
        InvitationQueryService service =
                new InvitationQueryServiceImpl(mapper, codec, noOwnerBadge());
        return MockMvcBuilders
                .standaloneSetup(new InvitationController(
                        service, null))
                .setControllerAdvice(new CommonExceptionHandler())
                .setMessageConverters(
                        new MappingJackson2HttpMessageConverter(ApiJsonMapper.create()))
                .addFilters(new TraceIdFilter())
                .build();
    }

    private static String toClassName(Path classFile) {
        String path = classFile.toString().replace('\\', '/');
        String withoutRoot = path.substring(path.indexOf("com/gighub/"));
        return withoutRoot.substring(0, withoutRoot.length() - ".class".length())
                .replace('/', '.');
    }

    private InvitationRow invitation(String status) {
        return InvitationRow.builder()
                .id(INVITATION_ID)
                .workCaseId(7L)
                .tokenHash(codec.hash(token))
                .status(InvitationStatus.valueOf(status))
                // 만료 시각을 과거로 두면 PENDING 경로에서 만료 전이가 먼저 일어납니다.
                .expiresAt(status.equals("PENDING") ? LocalDateTime.now().plusDays(1L) : STARTS_AT)
                .expectedTermsVersion(1)
                .build();
    }

    private static InvitationWorkCaseRow draftWorkCase() {
        return InvitationWorkCaseRow.builder()
                .workCaseId(7L)
                .employerId(3L)
                .workerId(null)
                .title("주말 홀 서빙")
                .workplaceName("강남점")
                .startsAt(STARTS_AT)
                .endsAt(STARTS_AT.plusHours(8L))
                .breakMinutes(60)
                .breakPaid(false)
                .dailyWage(120_000L)
                .termsVersion(1)
                .status(WorkCaseStatus.DRAFT)
                .build();
    }

    private static Authentication worker() {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(11L, UserRole.WORKER, "김알바"), "N/A", List.of());
    }

    private static Authentication owner() {
        return new UsernamePasswordAuthenticationToken(
                new AuthPrincipal(3L, UserRole.OWNER, "김사장"), "N/A", List.of());
    }

    /** 이 테스트는 Token 노출 여부만 확인하므로 배지 등급은 항상 0단계(null)로 고정합니다. */
    private static BadgeApplicationService noOwnerBadge() {
        return mock(
                BadgeApplicationService.class,
                invocation -> BadgeCalculationResult.of("TRUST_OWNER", 0, 0, 0, 0, 0, 10, 80));
    }

    /** 예상하지 못한 실패 경로까지 확인해야 해서 Mock 대신 직접 만든 Stub을 씁니다. */
    private static final class StubInvitationMapper extends InvitationMapperTestDouble {

        private InvitationRow invitation;
        private InvitationWorkCaseRow workCase;
        private boolean failUnexpectedly;

        @Override
        public InvitationRow findByTokenHashForUpdate(byte[] tokenHash) {
            if (failUnexpectedly) {
                // 내부 실패 메시지에 Token이 섞여도 응답과 로그로 새면 안 됩니다.
                throw new IllegalStateException("internal failure detail");
            }
            return invitation;
        }

        @Override
        public InvitationWorkCaseRow findWorkCaseForInvitation(long workCaseId) {
            return workCase;
        }

        @Override
        public int markExpired(long invitationId) {
            return 1;
        }
    }
}
