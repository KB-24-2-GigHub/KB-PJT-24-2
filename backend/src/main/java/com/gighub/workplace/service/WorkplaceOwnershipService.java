package com.gighub.workplace.service;

/** 타 모듈에 공개하는 최소 사업장 소유권·활성 경계입니다. */
public interface WorkplaceOwnershipService {

    boolean hasActiveOwnedWorkplace(Long ownerUserId);

    void requireOwnedActiveWorkplace(Long workplaceId, Long ownerUserId);

    /** 같은 outer Transaction에서 workplaces 행을 먼저 잠그며 소유권을 확인합니다. */
    void lockOwnedActiveWorkplace(Long workplaceId, Long ownerUserId);
}
