package com.gighub.wallet.service;

import java.time.LocalDateTime;

/**
 * 수락 outer Transaction에 참여해 OWNER 임금을 예치하는 Wallet 공개 명령입니다.
 *
 * <p>호출자는 Work와 Invitation을 먼저 잠급니다. 구현은 Work 상태를 다시 조회하거나
 * 변경하지 않고 Wallet, Escrow, 원장만 변경하므로 잠금 순서와 쓰기 소유권이 갈라지지
 * 않습니다.</p>
 */
public interface AcceptEscrowHold {

    /**
     * 일급만큼 사용 가능 잔액을 잠금 잔액으로 옮기고 에스크로와 원장을 기록합니다.
     *
     * @return 생성된 에스크로 식별자
     */
    long hold(
            long employerId,
            long workCaseId,
            long amount,
            long claimId,
            LocalDateTime acceptedAt);
}
