package com.gighub.work.domain;
/**
 * 근무 Case가 가질 수 있는 상태 8종입니다.
 *
 * <p>값과 순서는 Flyway Head의 {@code ck_work_cases_status} CHECK 제약과 같습니다.
 * REQUIREMENTS {@code WORK-001}이 요약에서 서로 구분하라고 정한 8개와도 정확히 일치하므로,
 * 상태별 요약은 이 enum의 {@link #values()}를 기준으로 0건 상태까지 채웁니다. DB가 돌려준
 * 행만 쓰면 건수가 0인 상태가 응답에서 조용히 사라집니다.</p>
 *
 * <p>DB에 저장된 문자열을 그대로 쓰기 위해 별도 코드 값을 두지 않습니다. MyBatis 기본
 * EnumTypeHandler가 상수 이름으로 매핑하므로, 상수 이름을 바꾸면 이미 저장된 값과
 * 어긋납니다.</p>
 *
 * <p>허용 명령과 상태 쌍은 {@link WorkCasePolicy}가 소유합니다. 이 enum에 분산된
 * {@code canXxx} 메서드를 늘리지 않아 Application이 어느 정책을 적용했는지 드러냅니다.</p>
 */
public enum WorkCaseStatus {
    DRAFT,
    ACCEPTED,
    READY,
    IN_PROGRESS,
    CHECK_OUT_MISSING,
    COMPLETED,
    NO_SHOW,
    CANCELED
}
