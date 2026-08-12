package com.gighub.wallet.service;

import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.common.exception.ValidationException;
import com.gighub.wallet.dto.WalletBalanceResponse;
import com.gighub.wallet.dto.WalletTransactionItem;
import com.gighub.wallet.dto.WalletTransactionSearch;
import com.gighub.wallet.mapper.WalletQueryMapper;
import com.gighub.wallet.mapper.result.WalletSummaryRow;
import com.gighub.wallet.mapper.result.WalletTransactionRow;
import com.gighub.wallet.service.command.WalletTransactionCriteria;
import com.gighub.wallet.service.impl.WalletQueryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 지갑 조회 Service의 사용자 격리, 필터 변환과 표시 파생을 검증합니다. */
@ExtendWith(MockitoExtension.class)
class WalletQueryServiceImplTest {

    private static final Long USER_ID = 3L;

    @Mock
    private WalletQueryMapper walletQueryMapper;

    private WalletQueryService walletQueryService;

    @BeforeEach
    void setUp() {
        walletQueryService = new WalletQueryServiceImpl(walletQueryMapper);
    }

    @Test
    void returnsApprovedWalletBalance() {
        when(walletQueryMapper.findWalletSummaryByUserId(USER_ID)).thenReturn(
                WalletSummaryRow.builder()
                        .walletId(30L)
                        .availableBalance(400_000L)
                        .lockedBalance(300_000L)
                        .build()
        );

        WalletBalanceResponse response = walletQueryService.getWallet(USER_ID);

        assertEquals("KRW", response.getCurrency());
        assertEquals(400_000L, response.getAvailableBalance());
        assertEquals(300_000L, response.getLockedBalance());
    }

    @Test
    void rejectsMissingWalletWithApprovedCommonError() {
        when(walletQueryMapper.findWalletSummaryByUserId(USER_ID)).thenReturn(null);

        assertThrows(ResourceNotFoundException.class,
                () -> walletQueryService.getWallet(USER_ID));
    }

    @Test
    void convertsDatesKeywordPaginationAndAuthenticatedUserScope() {
        when(walletQueryMapper.countTransactions(any())).thenReturn(0L);
        WalletTransactionCriteria criteria = criteriaBuilder()
                .workplaceId(9L)
                .from(LocalDate.of(2026, 7, 22))
                .to(LocalDate.of(2026, 7, 23))
                .type("FUNDING")
                .minAmount(1_000L)
                .maxAmount(5_000L)
                .keyword("  허브  ")
                .sort("AMOUNT_ASC")
                .page(2)
                .size(10)
                .build();

        PageResponse<WalletTransactionItem> page =
                walletQueryService.getTransactions(USER_ID, criteria);

        ArgumentCaptor<WalletTransactionSearch> captor =
                ArgumentCaptor.forClass(WalletTransactionSearch.class);
        verify(walletQueryMapper).countTransactions(captor.capture());
        WalletTransactionSearch search = captor.getValue();
        assertEquals(USER_ID, search.getUserId());
        assertEquals(9L, search.getWorkplaceId());
        assertEquals(LocalDateTime.of(2026, 7, 22, 0, 0), search.getFrom());
        assertEquals(LocalDateTime.of(2026, 7, 24, 0, 0), search.getToExclusive());
        assertEquals("FUNDING", search.getType());
        assertEquals(1_000L, search.getMinAmount());
        assertEquals(5_000L, search.getMaxAmount());
        assertEquals("허브", search.getKeyword());
        assertEquals("AMOUNT_ASC", search.getSort());
        assertEquals(20L, search.getOffset());
        assertEquals(10, search.getSize());
        assertEquals(0, page.getContent().size());
        assertEquals(2, page.getPage().getNumber());
        verify(walletQueryMapper, never()).findTransactions(any());
    }

    @Test
    void derivesDirectionStatusAndUtcFromLedgerBalances() {
        List<WalletTransactionRow> rows = List.of(
                view(1L, "FUNDING", 0, 100, 0, 0),
                view(2L, "ESCROW_HOLD", 100, 0, 0, 100),
                view(3L, "ESCROW_RELEASE", 0, 0, 100, 0),
                view(4L, "ESCROW_RELEASE", 0, 100, 0, 0),
                view(5L, "ESCROW_REFUND", 0, 100, 100, 0),
                view(6L, "WITHDRAWAL_REFUND", 0, 100, 0, 0)
        );
        when(walletQueryMapper.countTransactions(any())).thenReturn((long) rows.size());
        when(walletQueryMapper.findTransactions(any())).thenReturn(rows);

        PageResponse<WalletTransactionItem> page = walletQueryService.getTransactions(
                USER_ID,
                criteriaBuilder().build()
        );

        assertItem(page.getContent().get(0), "CREDIT", "COMPLETED");
        assertItem(page.getContent().get(1), "DEBIT", "COMPLETED");
        assertItem(page.getContent().get(2), "DEBIT", "COMPLETED");
        assertItem(page.getContent().get(3), "CREDIT", "COMPLETED");
        assertItem(page.getContent().get(4), "CREDIT", "REFUNDED");
        assertItem(page.getContent().get(5), "CREDIT", "REFUNDED");
        assertEquals("2026-07-22T04:00:00Z",
                page.getContent().get(0).getCreatedAt().toString());
    }

    @Test
    void rejectsReversedDateRangeWithToFieldError() {
        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> walletQueryService.getTransactions(
                        USER_ID,
                        criteriaBuilder()
                                .from(LocalDate.of(2026, 7, 24))
                                .to(LocalDate.of(2026, 7, 22))
                                .build()
                )
        );

        assertEquals("to", exception.getFieldErrors().get(0).getField());
        verify(walletQueryMapper, never()).countTransactions(any());
    }

    @Test
    void rejectsNegativeAmountWithMinAmountFieldError() {
        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> walletQueryService.getTransactions(
                        USER_ID,
                        criteriaBuilder().minAmount(-1L).build()
                )
        );

        assertEquals("minAmount", exception.getFieldErrors().get(0).getField());
    }

    @Test
    void rejectsReversedAmountRangeWithMaxAmountFieldError() {
        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> walletQueryService.getTransactions(
                        USER_ID,
                        criteriaBuilder().minAmount(5L).maxAmount(4L).build()
                )
        );

        assertEquals("maxAmount", exception.getFieldErrors().get(0).getField());
    }

    @Test
    void rejectsUnknownSortBeforeMapperCall() {
        ValidationException exception = assertThrows(
                ValidationException.class,
                () -> walletQueryService.getTransactions(
                        USER_ID,
                        criteriaBuilder().sort("id;DROP").build()
                )
        );

        assertEquals("sort", exception.getFieldErrors().get(0).getField());
        verify(walletQueryMapper, never()).countTransactions(any());
    }

    private WalletTransactionCriteria.WalletTransactionCriteriaBuilder criteriaBuilder() {
        return WalletTransactionCriteria.builder()
                .sort("LATEST")
                .page(0)
                .size(20);
    }

    private WalletTransactionRow view(
            Long id,
            String type,
            long availableBefore,
            long availableAfter,
            long lockedBefore,
            long lockedAfter) {
        return WalletTransactionRow.builder()
                .transactionId(id)
                .type(type)
                .amount(100L)
                .availableBefore(availableBefore)
                .availableAfter(availableAfter)
                .lockedBefore(lockedBefore)
                .lockedAfter(lockedAfter)
                .createdAt(LocalDateTime.of(2026, 7, 22, 13, 0))
                .build();
    }

    private void assertItem(
            WalletTransactionItem item,
            String expectedDirection,
            String expectedStatus) {
        assertEquals(expectedDirection, item.getDirection());
        assertEquals(expectedStatus, item.getDisplayStatus());
    }
}
