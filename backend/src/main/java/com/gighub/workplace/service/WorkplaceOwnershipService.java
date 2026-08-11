package com.gighub.workplace.service;

import com.gighub.workplace.service.result.WorkplaceLocationSnapshot;

/** 타 모듈에 공개하는 최소 사업장 소유권·활성 경계입니다. */
public interface WorkplaceOwnershipService {

    boolean hasActiveOwnedWorkplace(Long ownerUserId);

    void requireOwnedActiveWorkplace(Long workplaceId, Long ownerUserId);

    /** 같은 outer Transaction에서 workplaces 행을 먼저 잠그며 소유권을 확인합니다. */
    void lockOwnedActiveWorkplace(Long workplaceId, Long ownerUserId);

    /**
     * 소유권을 따지지 않고 활성 사업장 행을 잠근 뒤 현재 좌표를 읽습니다.
     *
     * <p>근태 스캔의 주체는 사업장을 소유하지 않은 WORKER라 소유권 기반 잠금을 쓸 수
     * 없습니다. 스캔은 QR로 사업장을 특정하고 배정 근무로 권한을 확인하므로, 여기서는
     * 활성 여부만 봅니다.</p>
     *
     * <p>승인된 잠금 순서 {@code workplaces -> qr_tokens -> work_cases}의 첫 단계입니다.
     * QR 재발급도 workplaces를 먼저 잠그므로 두 흐름이 같은 순서로 줄을 섭니다.</p>
     *
     * @return 활성 사업장이 아니면 {@code null}
     */
    WorkplaceLocationSnapshot lockActiveWorkplaceLocation(Long workplaceId);
}
