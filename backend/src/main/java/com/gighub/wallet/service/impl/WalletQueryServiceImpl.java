package com.gighub.wallet.service.impl;

import com.gighub.common.api.PageRequests;
import com.gighub.common.api.PageResponse;
import com.gighub.common.exception.ResourceNotFoundException;
import com.gighub.common.exception.ValidationException;
import com.gighub.wallet.dto.WalletBalanceResponse;
import com.gighub.wallet.dto.WalletTransactionItem;
import com.gighub.wallet.dto.WalletTransactionSearch;
import com.gighub.wallet.mapper.WalletQueryMapper;
import com.gighub.wallet.mapper.result.WalletSummaryRow;
import com.gighub.wallet.mapper.result.WalletTransactionRow;
import com.gighub.wallet.service.WalletQueryService;
import com.gighub.wallet.service.command.WalletTransactionCriteria;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

/** 지갑 조회의 검증, 영속 조회와 외부 응답 파생을 담당합니다. */
@Service
@RequiredArgsConstructor
public class WalletQueryServiceImpl implements WalletQueryService {

    private static final String CURRENCY_KRW = "KRW";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_REFUNDED = "REFUNDED";
    private static final String DIRECTION_CREDIT = "CREDIT";
    private static final String DIRECTION_DEBIT = "DEBIT";
    private static final Set<String> ALLOWED_SORTS =
            Set.of("LATEST", "OLDEST", "AMOUNT_ASC", "AMOUNT_DESC");
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "FUNDING", "ESCROW_HOLD", "ESCROW_RELEASE", "ESCROW_REFUND",
            "WITHDRAWAL", "WITHDRAWAL_REFUND", "ADJUSTMENT");

    private final WalletQueryMapper walletQueryMapper;

    @Override
    public WalletBalanceResponse getWallet(Long userId) {
        WalletSummaryRow summary = walletQueryMapper.findWalletSummaryByUserId(userId);
        if (summary == null) {
            throw new ResourceNotFoundException("지갑을 찾을 수 없습니다.");
        }
        return WalletBalanceResponse.of(
                CURRENCY_KRW,
                summary.getAvailableBalance(),
                summary.getLockedBalance());
    }

    @Override
    public PageResponse<WalletTransactionItem> getTransactions(
            Long userId,
            WalletTransactionCriteria criteria) {
        validate(criteria);

        String keyword = normalizeKeyword(criteria.getKeyword());
        WalletTransactionSearch search = WalletTransactionSearch.builder()
                .userId(userId)
                .workplaceId(criteria.getWorkplaceId())
                .from(criteria.getFrom() == null ? null : criteria.getFrom().atStartOfDay())
                // LocalDate 상한은 다음 날 자정 미만으로 바꿔 to 날짜 전체를 포함한다.
                .toExclusive(criteria.getTo() == null
                        ? null : criteria.getTo().plusDays(1).atStartOfDay())
                .type(criteria.getType())
                .minAmount(criteria.getMinAmount())
                .maxAmount(criteria.getMaxAmount())
                .keyword(keyword)
                .sort(criteria.getSort())
                .offset(PageRequests.offset(criteria.getPage(), criteria.getSize()))
                .size(criteria.getSize())
                .build();

        long totalElements = walletQueryMapper.countTransactions(search);
        List<WalletTransactionItem> content = totalElements == 0
                ? List.of()
                : walletQueryMapper.findTransactions(search).stream()
                        .map(this::toItem)
                        .toList();

        return PageResponse.of(
                content,
                criteria.getPage(),
                criteria.getSize(),
                totalElements
        );
    }

    private void validate(WalletTransactionCriteria criteria) {
        PageRequests.validate(criteria.getPage(), criteria.getSize());
        if (criteria.getWorkplaceId() != null && criteria.getWorkplaceId() <= 0) {
            throw validation("workplaceId", "workplaceId는 1 이상이어야 합니다.");
        }
        if (!ALLOWED_SORTS.contains(criteria.getSort())) {
            throw validation("sort", "지원하지 않는 sort 값입니다.");
        }
        if (criteria.getType() != null && !ALLOWED_TYPES.contains(criteria.getType())) {
            throw validation("type", "지원하지 않는 type 값입니다.");
        }
        if (criteria.getFrom() != null && criteria.getTo() != null
                && criteria.getFrom().isAfter(criteria.getTo())) {
            throw validation("to", "to는 from과 같거나 이후여야 합니다.");
        }
        if (criteria.getMinAmount() != null && criteria.getMinAmount() < 0) {
            throw validation("minAmount", "minAmount는 0 이상이어야 합니다.");
        }
        if (criteria.getMaxAmount() != null && criteria.getMaxAmount() < 0) {
            throw validation("maxAmount", "maxAmount는 0 이상이어야 합니다.");
        }
        if (criteria.getMinAmount() != null && criteria.getMaxAmount() != null
                && criteria.getMinAmount() > criteria.getMaxAmount()) {
            throw validation("maxAmount", "maxAmount는 minAmount 이상이어야 합니다.");
        }
    }

    private ValidationException validation(String field, String reason) {
        return new ValidationException("입력값을 확인해 주세요.", field, reason);
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return null;
        }
        return keyword.trim();
    }

    private WalletTransactionItem toItem(WalletTransactionRow row) {
        // 원장 계산은 Row로 끝낸 뒤 기존 공개 필드만 응답에 복사합니다.
        return WalletTransactionItem.of(
                row.getTransactionId(),
                row.getType(),
                row.getAmount(),
                resolveDirection(row),
                row.getAvailableAfter(),
                row.getLockedAfter(),
                row.getWorkCaseId(),
                row.getWorkTitle(),
                row.getWorkplaceName(),
                resolveDisplayStatus(row.getType()),
                row.getCreatedAt());
    }

    private String resolveDirection(WalletTransactionRow row) {
        BigInteger beforeTotal = sum(row.getAvailableBefore(), row.getLockedBefore());
        BigInteger afterTotal = sum(row.getAvailableAfter(), row.getLockedAfter());
        int totalChange = afterTotal.compareTo(beforeTotal);
        if (totalChange > 0) {
            return DIRECTION_CREDIT;
        }
        if (totalChange < 0) {
            return DIRECTION_DEBIT;
        }

        // 예치·환불처럼 총액이 같으면 가용 잔액 방향이 사용자 체감 증감을 결정한다.
        int availableChange = row.getAvailableAfter().compareTo(row.getAvailableBefore());
        if (availableChange > 0) {
            return DIRECTION_CREDIT;
        }
        if (availableChange < 0) {
            return DIRECTION_DEBIT;
        }
        throw new IllegalStateException("증감이 없는 지갑 원장 행입니다.");
    }

    private BigInteger sum(Long available, Long locked) {
        return BigInteger.valueOf(available).add(BigInteger.valueOf(locked));
    }

    private String resolveDisplayStatus(String type) {
        if ("ESCROW_REFUND".equals(type) || "WITHDRAWAL_REFUND".equals(type)) {
            return STATUS_REFUNDED;
        }
        if (ALLOWED_TYPES.contains(type)) {
            return STATUS_COMPLETED;
        }
        throw new IllegalStateException("승인되지 않은 지갑 거래 유형입니다.");
    }
}
