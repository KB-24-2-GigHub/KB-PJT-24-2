package com.gighub.work.service;

/**
 * 다른 모듈이 근무 참여 여부만 물어보는 읽기 전용 경계입니다.
 *
 * <p>근무 목록·상세 조회는 {@link WorkCaseService}가 소유합니다. 이 인터페이스는 근무 내용
 * 자체를 내보내지 않고 "이 사용자에게 끝나지 않은 근무가 몇 건 있는가"만 답합니다. 호출자가
 * 근무 조건을 읽을 수 없으므로 소유권 검사 없이 사용자 식별자만으로 물어볼 수 있습니다.</p>
 */
public interface WorkParticipationQueryService {

    /**
     * 사장·근로자 어느 쪽으로 묶였든 아직 끝나지 않은 근무 수를 셉니다.
     *
     * <p>종료 상태({@code COMPLETED}, {@code NO_SHOW}, {@code CANCELED})는 세지 않습니다.
     * 그 상태에 남아 있는 돈은 근무가 아니라 지갑의 예치금으로 드러납니다.</p>
     *
     * @param userId 판정 대상 사용자 식별자
     * @return 끝나지 않은 근무 수. 없으면 0
     */
    int countUnfinished(Long userId);
}
