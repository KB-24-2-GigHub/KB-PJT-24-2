package com.gighub.wallet.service;

import com.gighub.common.api.PageResponse;
import com.gighub.wallet.dto.WalletBalanceResponse;
import com.gighub.wallet.dto.WalletTransactionItem;
import com.gighub.wallet.service.command.WalletTransactionCriteria;

/** 인증 사용자의 지갑 잔액과 원장 조회를 제공합니다. */
public interface WalletQueryService {

    WalletBalanceResponse getWallet(Long userId);

    /**
     * 지갑이 없어도 실패하지 않는 잔액 스냅샷입니다.
     *
     * <p>{@link #getWallet}은 화면이 여는 지갑 탭의 질의라 지갑이 없으면 실패로 끝납니다.
     * 반면 "이 사용자에게 남은 돈이 있는가"를 묻는 호출자에게 지갑 부재는 실패가 아니라
     * 0원이므로, 두 질의를 나눠 부재를 예외로 다루지 않게 합니다.</p>
     *
     * @param userId 조회 대상 사용자 식별자
     * @return 현재 잔액. 지갑이 없으면 두 값 모두 0
     */
    WalletBalanceResponse getBalanceSnapshot(Long userId);

    PageResponse<WalletTransactionItem> getTransactions(
            Long userId,
            WalletTransactionCriteria criteria);
}
