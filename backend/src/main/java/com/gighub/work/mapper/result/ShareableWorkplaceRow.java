package com.gighub.work.mapper.result;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * 보건증 신규 공유 후보가 될 수 있는 근무 관계 한 행입니다.
 *
 * <p>사업장 하나가 아니라 관계 하나가 한 행입니다. 같은 사업장에 후보 Work Case가 둘 이상이면
 * 그만큼 행이 나오고, 그 상태에서 실제 공유 요청은 후보를 임의로 고르지 않고 409로 거부합니다
 * (DEC-DOCUMENT-SHARE-UNIT). 그래서 여기서 미리 사업장 단위로 합치지 않습니다.</p>
 *
 * <p>MyBatis가 &lt;constructor&gt; 매핑으로 생성하므로 필드 선언 순서가 곧 생성자 인자
 * 순서입니다. XML과 함께 바꿔야 합니다.</p>
 */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor
public class ShareableWorkplaceRow {

    private final Long workplaceId;
    private final String workplaceName;
    private final String ownerName;
    private final LocalDateTime startsAt;
    private final LocalDateTime endsAt;
}
