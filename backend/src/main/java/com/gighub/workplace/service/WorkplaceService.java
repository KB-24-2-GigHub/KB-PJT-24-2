package com.gighub.workplace.service;

import com.gighub.auth.security.AuthPrincipal;
import com.gighub.common.api.PageResponse;
import com.gighub.workplace.dto.WorkplaceListItemResponse;
import com.gighub.workplace.service.command.WorkplaceCreateCommand;

/** 사업장 등록과 소유 사업장 조회의 승인 규칙을 적용합니다. */
public interface WorkplaceService {

    /**
     * 인증 OWNER의 사업장을 등록하고 생성된 식별자를 반환합니다.
     *
     * @param principal 소유자를 결정하는 인증 Principal. 요청 Body는 소유자를 정할 수 없습니다.
     * @param command   검증을 통과한 등록 입력
     * @return 생성된 사업장 식별자
     */
    Long create(AuthPrincipal principal, WorkplaceCreateCommand command);

    /**
     * 인증 OWNER가 소유한 관리 대상 사업장 한 Page를 조회합니다.
     *
     * <p>Page 경계 검증과 건수 계산을 Controller가 아닌 여기에서 하는 이유는, 목록 SQL과 건수
     * SQL이 항상 같은 조건·같은 Page 값으로 묶여야 하기 때문입니다. 둘이 어긋나면 총 건수와
     * 실제 내용이 다른 응답이 조용히 나갑니다.</p>
     *
     * @param principal 소유자를 결정하는 인증 Principal. Query는 소유자를 정할 수 없습니다.
     * @param page      0-based Page 번호
     * @param size      Page 크기
     * @return 승인된 Page Envelope payload. 사업장이 없으면 빈 {@code content}
     */
    PageResponse<WorkplaceListItemResponse> findOwnedWorkplaces(
            AuthPrincipal principal, int page, int size);
}
