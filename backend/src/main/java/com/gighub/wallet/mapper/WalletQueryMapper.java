package com.gighub.wallet.mapper;

import com.gighub.wallet.dto.WalletTransactionSearch;
import com.gighub.wallet.mapper.result.WalletSummaryRow;
import com.gighub.wallet.mapper.result.WalletTransactionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 지갑 조회 전용 Mapper.
 * 읽기 API가 잔액 행을 잠그면 충전·예치가 대기하게 되므로 FOR UPDATE를 사용하지 않는다.
 * 잠금이 필요한 쓰기 경로는 WalletMapper를 사용한다.
 */
@Mapper
public interface WalletQueryMapper {
    // available/locked를 한 번의 SELECT로 함께 읽어 두 값의 시점이 갈리지 않게 한다.
    WalletSummaryRow findWalletSummaryByUserId(@Param("userId") Long userId);

    List<WalletTransactionRow> findTransactions(WalletTransactionSearch search);

    long countTransactions(WalletTransactionSearch search);
}
