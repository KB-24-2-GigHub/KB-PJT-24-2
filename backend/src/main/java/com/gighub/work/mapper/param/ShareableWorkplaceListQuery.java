package com.gighub.work.mapper.param;

import lombok.Builder;
import lombok.Getter;

/**
 * 보건증 공유 가능 사업장 조회 조건입니다.
 *
 * <p>건수 SQL과 목록 SQL이 같은 객체를 받도록 해, 두 쿼리의 {@code WHERE}가 서로 다른 값으로
 * 어긋나는 것을 막습니다.</p>
 */
@Getter
@Builder
public final class ShareableWorkplaceListQuery {

    private final Long workerId;
    private final int size;
    private final long offset;
}
