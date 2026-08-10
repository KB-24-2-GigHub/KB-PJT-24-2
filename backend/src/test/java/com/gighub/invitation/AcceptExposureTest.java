package com.gighub.invitation;

import com.gighub.invitation.domain.InvitationStatus;
import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.exception.ApiException;
import com.gighub.common.exception.ConflictException;
import com.gighub.contract.ContractArtifactCommand;
import com.gighub.contract.ContractArtifactHandle;
import com.gighub.contract.ContractArtifactPort;
import com.gighub.idempotency.IdempotencyClaimResult;
import com.gighub.idempotency.IdempotencyClaimService;
import com.gighub.idempotency.exception.IdempotencyClaimKeyReusedException;
import com.gighub.invitation.config.InvitationProperties;
import com.gighub.invitation.exception.InvitationExpiredException;
import com.gighub.invitation.mapper.InvitationMapperTestDouble;
import com.gighub.invitation.mapper.result.InvitationRow;
import com.gighub.invitation.service.InvitationAcceptService;
import com.gighub.invitation.service.impl.AcceptJson;
import com.gighub.invitation.service.impl.InvitationAcceptServiceImpl;
import com.gighub.invitation.token.InvitationTokenCodec;
import com.gighub.member.domain.UserRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 수락 흐름이 Token과 {@code Idempotency-Key}를 밖으로 흘리지 않는지 확인하는 회귀 검사입니다.
 *
 * <p>두 값 모두 그 자체가 인증 수단입니다. Token은 가진 사람이 근무를 확정할 수 있고, Key는
 * 저장된 성공 응답을 다시 받아낼 수 있는 열쇠입니다. 한 번이라도 로그나 오류 Body에 남으면
 * 저장소에 Hash와 Fingerprint만 보관한 노력이 무의미해집니다.</p>
 *
 * <p>초대 패키지는 {@code InvitationTokenExposureTest}가 이미 다루므로 여기서는 수락이 새로
 * 쓰는 멱등·계약 패키지를 확인합니다.</p>
 */
class AcceptExposureTest {

    private static final long INVITATION_ID = 41L;
    private static final long WORK_CASE_ID = 7L;
    private static final String KEY = "accept-key-secret-0001";

    private final InvitationTokenCodec codec = new InvitationTokenCodec(
            InvitationProperties.of(
                    "exposure-accept-secret-01234567890123", null, "http://localhost:5173")
    );
    private final String token = codec.deriveToken(INVITATION_ID);
    private final StubClaimService claims = new StubClaimService();
    private final StubInvitationMapper mapper = new StubInvitationMapper();

    @Test
    void idempotencyAndContractPackagesDeclareNoLoggerAtAll() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String domain : new String[]{"idempotency", "contract"}) {
            offenders.addAll(loggerFieldsIn(domain));
        }

        // Logger를 두지 않으면 Token·Key가 실린 로그 문장이 생길 경로 자체가 없습니다.
        assertTrue(offenders.isEmpty(), "Logger가 있으면 안 됩니다: " + offenders);
    }

    @Test
    void everyAcceptFailureKeepsTokenAndKeyOutOfItsMessage() {
        mapper.invitation = pendingInvitation();
        List<ApiException> failures = new ArrayList<>();

        // 역할 거절
        failures.add(assertThrows(
                ApiException.class, () -> service().accept(owner(), token, KEY)));
        // 형식 오류 Token
        failures.add(assertThrows(
                ApiException.class, () -> service().accept(worker(), "not-a-token", KEY)));
        // 선점 이후의 상태 오류
        failures.add(assertThrows(
                ApiException.class, () -> service().accept(worker(), token, KEY)));

        // 같은 Key 재사용
        claims.failure = new IdempotencyClaimKeyReusedException();
        failures.add(assertThrows(
                ApiException.class, () -> service().accept(worker(), token, KEY)));

        // 처리 중 충돌
        claims.failure = new ConflictException("같은 요청을 처리하고 있습니다.");
        failures.add(assertThrows(
                ApiException.class, () -> service().accept(worker(), token, KEY)));

        // 미존재 Token
        claims.failure = null;
        mapper.invitation = null;
        failures.add(assertThrows(
                ApiException.class, () -> service().accept(worker(), token, KEY)));

        for (ApiException failure : failures) {
            assertFalse(
                    failure.getMessage().contains(token),
                    failure.getClass().getSimpleName() + " 메시지에 Token이 남으면 안 됩니다.");
            assertFalse(
                    failure.getMessage().contains(KEY),
                    failure.getClass().getSimpleName() + " 메시지에 Key가 남으면 안 됩니다.");
        }
    }

    @Test
    void storedFingerprintCannotRevealTheTokenOrTheKey() {
        mapper.invitation = pendingInvitation();

        assertThrows(ApiException.class, () -> service().accept(worker(), token, KEY));

        String fingerprintText = new String(claims.fingerprint, StandardCharsets.ISO_8859_1);
        // Fingerprint 입력은 Token Hash Hex와 조건 Version뿐이라 저장해도 원문을 복원할 수
        // 없습니다.
        assertFalse(fingerprintText.contains(token));
        assertFalse(fingerprintText.contains(KEY));
    }

    private InvitationAcceptService service() {
        return new InvitationAcceptServiceImpl(
                mapper,
                codec,
                claims,
                null,
                new AcceptJson(),
                new StubArtifactPort());
    }

    private List<String> loggerFieldsIn(String domain) throws Exception {
        Path classes = Paths.get("build", "classes", "java", "main", "com", "gighub", domain);
        assertTrue(Files.isDirectory(classes), "컴파일된 " + domain + " Class가 있어야 합니다.");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(classes)) {
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".class"))
                    .toList()) {
                Class<?> type = Class.forName(toClassName(path));
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType().getName().endsWith("Logger")) {
                        offenders.add(type.getName() + "." + field.getName());
                    }
                }
            }
        }
        return offenders;
    }

    private static String toClassName(Path classFile) {
        String path = classFile.toString().replace('\\', '/');
        String withoutRoot = path.substring(path.indexOf("com/gighub/"));
        return withoutRoot.substring(0, withoutRoot.length() - ".class".length())
                .replace('/', '.');
    }

    private InvitationRow pendingInvitation() {
        return InvitationRow.builder()
                .id(INVITATION_ID)
                .workCaseId(WORK_CASE_ID)
                .tokenHash(codec.hash(token))
                .status(InvitationStatus.PENDING)
                .expectedTermsVersion(1)
                .expiresAt(LocalDateTime.now().plusDays(1L))
                .build();
    }

    private static AuthPrincipal worker() {
        return new AuthPrincipal(11L, UserRole.WORKER, "김알바");
    }

    private static AuthPrincipal owner() {
        return new AuthPrincipal(3L, UserRole.OWNER, "김사장");
    }

    private static final class StubInvitationMapper extends InvitationMapperTestDouble {

        private InvitationRow invitation;

        @Override
        public InvitationRow findByTokenHash(byte[] tokenHash) {
            return invitation;
        }
    }

    /** 선점 단계에서 실패를 주입하고 Fingerprint를 관찰합니다. */
    private static final class StubClaimService implements IdempotencyClaimService {

        private byte[] fingerprint = new byte[0];
        private RuntimeException failure;

        @Override
        public IdempotencyClaimResult claim(
                long userId, String operationCode, String rawKey, byte[] fingerprint) {
            this.fingerprint = fingerprint;
            if (failure != null) {
                throw failure;
            }
            // 본 처리로 넘어가지 않도록 여기서 끊습니다. 이 테스트의 관심사는 메시지입니다.
            throw new InvitationExpiredException();
        }

        @Override
        public void complete(long claimId, int responseHttpStatus, String responseBody) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abandon(long claimId) {
        }
    }

    /** 파일 준비는 이 테스트의 관심사가 아닙니다. */
    private static final class StubArtifactPort implements ContractArtifactPort {

        @Override
        public ContractArtifactHandle prepare(ContractArtifactCommand command) {
            return ContractArtifactHandle.nothing();
        }

        @Override
        public void promote(ContractArtifactHandle handle) {
        }

        @Override
        public void discardPending(long workCaseId) {
        }
    }
}
