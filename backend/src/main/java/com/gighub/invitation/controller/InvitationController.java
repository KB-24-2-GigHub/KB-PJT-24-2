package com.gighub.invitation.controller;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.auth.security.AuthPrincipals;
import com.gighub.common.api.ApiResponse;
import com.gighub.common.api.RequestBodies;
import com.gighub.invitation.dto.InvitationAcceptResponse;
import com.gighub.invitation.dto.InvitationDetailResponse;
import com.gighub.invitation.service.InvitationAcceptResult;
import com.gighub.invitation.service.InvitationAcceptService;
import com.gighub.invitation.service.InvitationQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

/**
 * 초대 Link 조회와 수락을 제공하는 Controller입니다.
 *
 * <p>두 Endpoint 모두 공개 경로가 아니므로 비인증 요청은 공통 Security 경계에서
 * {@code 401 AUTH_REQUIRED}로 끝납니다. 역할 경계는 Service가 확인해
 * {@code 403 ROLE_MISMATCH}로 구분합니다.</p>
 *
 * <p>Frontend는 비로그인 웹 접근을 {@code /worker/login?redirect={encodedInvitationPath}}로
 * 보낸 뒤 같은 경로로 복귀합니다. 서버는 복귀 요청에서 Token과 상태를 다시 검증합니다.</p>
 */
@RestController
public class InvitationController {

    /** 저장된 결과를 다시 보냈음을 알리는 승인 Header입니다. */
    private static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private final InvitationQueryService invitationQueryService;
    private final InvitationAcceptService invitationAcceptService;

    public InvitationController(
            InvitationQueryService invitationQueryService,
            InvitationAcceptService invitationAcceptService) {
        this.invitationQueryService = invitationQueryService;
        this.invitationAcceptService = invitationAcceptService;
    }

    /**
     * Token이 가리키는 초대의 근무 조건을 반환합니다.
     *
     * <p>Token은 경로 값으로만 받고 응답에 되돌려 담지 않습니다.</p>
     */
    @GetMapping("/api/invitations/{token}")
    public ResponseEntity<ApiResponse<InvitationDetailResponse>> findByToken(
            @PathVariable String token,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);

        return ResponseEntity.ok(
                ApiResponse.of(invitationQueryService.findByToken(principal, token))
        );
    }

    /**
     * 초대를 수락해 매칭·계약·임금 예치·정산 예약을 한 번에 확정합니다.
     *
     * <p>Body를 받지 않습니다. {@code @RequestBody}를 두지 않는 것만으로는 client가 보낸 값이
     * 조용히 무시될 뿐이라, 실제로 0byte인지 확인하고 아니면 거절합니다. 사용자·근무·금액
     * ID와 이름, 서명 이미지는 어떤 형태로도 받지 않는다는 뜻입니다.</p>
     *
     * <p>최초 성공과 저장된 결과 재생 모두 200이며 Body가 같습니다. 재생에만
     * {@code Idempotency-Replayed: true}를 붙여 호출자가 둘을 구분할 수 있게 합니다.</p>
     */
    @PostMapping("/api/invitations/{token}/accept")
    public ResponseEntity<ApiResponse<InvitationAcceptResponse>> accept(
            @PathVariable String token,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request,
            Authentication authentication) {
        AuthPrincipal principal = AuthPrincipals.resolve(authentication);
        RequestBodies.requireEmpty(request);

        InvitationAcceptResult result =
                invitationAcceptService.accept(principal, token, idempotencyKey);

        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (result.isReplayed()) {
            response.header(REPLAYED_HEADER, "true");
        }
        return response.body(ApiResponse.of(InvitationAcceptResponse.of(
                result.getResult().getWorkCaseId(),
                result.getResult().getEscrowStatus())));
    }
}
