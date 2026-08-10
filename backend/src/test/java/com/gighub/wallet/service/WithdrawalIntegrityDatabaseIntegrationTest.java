package com.gighub.wallet.service;

import com.gighub.bank.service.BankAccountPreflightCommand;
import com.gighub.bank.service.BankTransferGateway;
import com.gighub.config.RootConfig;
import com.gighub.wallet.dto.WalletBalanceSnapshot;
import com.gighub.wallet.dto.WithdrawalOrder;
import com.gighub.wallet.exception.InsufficientAvailableBalanceException;
import com.gighub.wallet.idempotency.WalletIdempotencyKeys;
import com.gighub.wallet.mapper.WalletMapper;
import com.gighub.wallet.mapper.WithdrawalMapper;
import com.gighub.wallet.mapper.param.WithdrawalOrderParam;
import com.gighub.wallet.service.command.WithdrawalCommand;
import com.gighub.wallet.service.result.WithdrawalResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("database")
public class WithdrawalIntegrityDatabaseIntegrationTest {
    @Test
    @Timeout(15)
    void insufficientAvailableBalanceRollsBackClaimAndMoneyMovement() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            WithdrawalService withdrawalService = context.getBean(WithdrawalService.class);
            WithdrawalFixture fixture = createWithdrawalFixture(jdbcTemplate);
            WithdrawalCommand command = WithdrawalCommand.builder()
                    .userId(fixture.userId())
                    .bankCode(FIXTURE_BANK_CODE)
                    .accountNo(fixture.accountNo())
                    .amount(1_000L)
                    .idempotencyKey(fixture.idempotencyKey())
                    .build();

            try {
                jdbcTemplate.update(
                        "UPDATE wallets SET available_balance = 500 WHERE id = ?",
                        fixture.walletId());

                assertThrows(
                        InsufficientAvailableBalanceException.class,
                        () -> withdrawalService.withdraw(command));

                assertEquals(500L, value(jdbcTemplate,
                        "SELECT available_balance FROM wallets WHERE id = ?",
                        fixture.walletId()));
                assertEquals(1_000_000L, value(jdbcTemplate,
                        "SELECT balance FROM mock_bank_accounts WHERE id = ?",
                        fixture.accountId()));
                assertEquals(0, count(jdbcTemplate,
                        "SELECT COUNT(*) FROM withdrawal_requests WHERE user_id = ?",
                        fixture.userId()));
                assertEquals(0, count(jdbcTemplate,
                        "SELECT COUNT(*) FROM wallet_transactions WHERE wallet_id = ?",
                        fixture.walletId()));
                assertEquals(0, count(jdbcTemplate,
                        "SELECT COUNT(*) FROM mock_bank_transactions WHERE account_id = ?",
                        fixture.accountId()));
            } finally {
                deleteWithdrawalFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(15)
    void concurrentWithdrawalMovesMoneyOnceAndReplaysOriginalSnapshot() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            WithdrawalService withdrawalService = context.getBean(WithdrawalService.class);
            WithdrawalFixture fixture = createWithdrawalFixture(jdbcTemplate);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);

            // 1,000원 출금 요청
            WithdrawalCommand command = WithdrawalCommand.builder()
                    .userId(fixture.userId())
                    .bankCode(FIXTURE_BANK_CODE)
                    .accountNo(fixture.accountNo())
                    .amount(1_000L)
                    .idempotencyKey(fixture.idempotencyKey())
                    .build();

            try {
                Future<WithdrawalResult> first = executor.submit(() -> {
                    await(start);
                    return withdrawalService.withdraw(command);
                });
                Future<WithdrawalResult> second = executor.submit(() -> {
                    await(start);
                    return withdrawalService.withdraw(command);
                });
                start.countDown();

                WithdrawalResult firstResult = first.get(8, TimeUnit.SECONDS);
                WithdrawalResult secondResult = second.get(8, TimeUnit.SECONDS);

                // 하나는 원본 처리, 하나는 Replay 응답이어야 함
                assertTrue(firstResult.isReplayed() ^ secondResult.isReplayed());
                assertEquals(firstResult.getWithdrawalRequestId(), secondResult.getWithdrawalRequestId());
                assertEquals(firstResult.getBankTransactionId(), secondResult.getBankTransactionId());

                // 원장 및 DB 상태 검증
                assertEquals(1, count(jdbcTemplate,
                        "SELECT COUNT(*) FROM withdrawal_requests WHERE idempotency_key = ?",
                        fixture.idempotencyKey()));
                assertEquals(1, count(jdbcTemplate,
                        "SELECT COUNT(*) FROM mock_bank_transactions WHERE account_id = ?",
                        fixture.accountId()));
                assertEquals(1, count(jdbcTemplate,
                        "SELECT COUNT(*) FROM wallet_transactions"
                                + " WHERE wallet_id = ? AND transaction_type = 'WITHDRAWAL'",
                        fixture.walletId()));

                // 출금이므로 은행 계좌는 1,000원 증가해야 함 (초기 1,000,000 + 1,000)
                assertEquals(1_001_000L, value(jdbcTemplate,
                        "SELECT balance FROM mock_bank_accounts WHERE id = ?",
                        fixture.accountId()));
                // 지갑은 1,000원 감소해야 함 (초기 10,000 - 1,000)
                assertEquals(9_000L, value(jdbcTemplate,
                        "SELECT available_balance FROM wallets WHERE id = ?",
                        fixture.walletId()));

                // Replay 검증
                WithdrawalResult replayed = withdrawalService.withdraw(command);
                assertTrue(replayed.isReplayed());
                assertEquals(firstResult.getWithdrawalRequestId(), replayed.getWithdrawalRequestId());
            } finally {
                start.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                deleteWithdrawalFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(15)
    void completedReplayIgnoresDrainedWalletAndBlockedAccount() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            WithdrawalService withdrawalService = context.getBean(WithdrawalService.class);
            WithdrawalFixture fixture = createWithdrawalFixture(jdbcTemplate);
            WithdrawalCommand command = WithdrawalCommand.builder()
                    .userId(fixture.userId())
                    .bankCode(FIXTURE_BANK_CODE)
                    .accountNo(fixture.accountNo())
                    .amount(1_000L)
                    .idempotencyKey(fixture.idempotencyKey())
                    .build();

            try {
                jdbcTemplate.update(
                        "UPDATE wallets SET available_balance = 1000 WHERE id = ?",
                        fixture.walletId()
                );

                WithdrawalResult original = withdrawalService.withdraw(command);
                assertFalse(original.isReplayed());
                assertEquals(0L, value(jdbcTemplate,
                        "SELECT available_balance FROM wallets WHERE id = ?",
                        fixture.walletId()));

                // 첫 요청 뒤 계좌를 차단해도 replay는 현재 상태가 아닌 저장 결과를 반환해야 한다.
                jdbcTemplate.update(
                        "UPDATE mock_bank_accounts SET status = 'BLOCKED' WHERE id = ?",
                        fixture.accountId()
                );

                WithdrawalResult replayed = withdrawalService.withdraw(command);

                assertTrue(replayed.isReplayed());
                assertEquals(original.getWithdrawalRequestId(),
                        replayed.getWithdrawalRequestId());
                assertEquals(original.getBankTransactionId(),
                        replayed.getBankTransactionId());
                assertEquals(1, count(jdbcTemplate,
                        "SELECT COUNT(*) FROM withdrawal_requests WHERE user_id = ?",
                        fixture.userId()));
                assertEquals(1, count(jdbcTemplate,
                        "SELECT COUNT(*) FROM mock_bank_transactions WHERE account_id = ?",
                        fixture.accountId()));
                assertEquals(1, count(jdbcTemplate,
                        "SELECT COUNT(*) FROM wallet_transactions"
                                + " WHERE wallet_id = ? AND transaction_type = 'WITHDRAWAL'",
                        fixture.walletId()));
                assertEquals(0L, value(jdbcTemplate,
                        "SELECT available_balance FROM wallets WHERE id = ?",
                        fixture.walletId()));
                assertEquals(1_001_000L, value(jdbcTemplate,
                        "SELECT balance FROM mock_bank_accounts WHERE id = ?",
                        fixture.accountId()));
            } finally {
                deleteWithdrawalFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(15)
    void concurrentDuplicateCanReadCommittedClaimWithForShare() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            WithdrawalMapper withdrawalMapper = context.getBean(WithdrawalMapper.class);
            PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));

            WithdrawalFixture fixture = createWithdrawalFixture(jdbcTemplate);
            String key = "IT-WD-" + UUID.randomUUID();
            CountDownLatch ownerInserted = new CountDownLatch(1);
            CountDownLatch duplicateStarted = new CountDownLatch(1);
            CountDownLatch releaseOwner = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);

            try {
                Future<Long> owner = executor.submit(() ->
                        new TransactionTemplate(transactionManager).execute(status -> {
                            WithdrawalOrderParam param = WithdrawalOrderParam.builder()
                                    .userId(fixture.userId())
                                    .walletId(fixture.walletId())
                                    .linkedAccountId(fixture.accountId())
                                    .amount(1_000L)
                                    .idempotencyKey(key)
                                    .build();
                            assertEquals(1, withdrawalMapper.insertWithdrawalRequest(param));
                            assertNotNull(param.getId());
                            ownerInserted.countDown();
                            await(releaseOwner);
                            return param.getId();
                        })
                );

                Future<WithdrawalOrder> duplicate = executor.submit(() -> {
                    await(ownerInserted);
                    return new TransactionTemplate(transactionManager).execute(status -> {
                        duplicateStarted.countDown();
                        try {
                            WithdrawalOrderParam param = WithdrawalOrderParam.builder()
                                    .userId(fixture.userId())
                                    .walletId(fixture.walletId())
                                    .linkedAccountId(fixture.accountId())
                                    .amount(1_000L)
                                    .idempotencyKey(key)
                                    .build();
                            withdrawalMapper.insertWithdrawalRequest(param);
                            throw new AssertionError("중복 멱등 키 INSERT가 성공했습니다.");
                        } catch (DuplicateKeyException expected) {
                            return withdrawalMapper.findByIdempotencyKeyForShare(key);
                        }
                    });
                });

                assertTrue(duplicateStarted.await(3, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> duplicate.get(200, TimeUnit.MILLISECONDS));
                releaseOwner.countDown();

                Long ownerId = owner.get(5, TimeUnit.SECONDS);
                WithdrawalOrder replayed = duplicate.get(5, TimeUnit.SECONDS);

                assertNotNull(replayed);
                assertEquals(ownerId, replayed.getId());
                assertEquals(fixture.userId(), replayed.getUserId());
                assertEquals(fixture.accountId(), replayed.getLinkedAccountId());
                assertEquals(1_000L, replayed.getAmount());
            } finally {
                releaseOwner.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                jdbcTemplate.update("DELETE FROM withdrawal_requests WHERE idempotency_key = ?", key);
                deleteWithdrawalFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(20)
    void ownerRollbackLetsTwoWaitersRetryAndCompleteOnce() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            WithdrawalService withdrawalService = context.getBean(WithdrawalService.class);
            WithdrawalMapper withdrawalMapper = context.getBean(WithdrawalMapper.class);
            PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
            WithdrawalFixture fixture = createWithdrawalFixture(jdbcTemplate);
            ExecutorService executor = Executors.newFixedThreadPool(3);
            CountDownLatch ownerInserted = new CountDownLatch(1);
            CountDownLatch releaseOwner = new CountDownLatch(1);
            CountDownLatch waitersStarted = new CountDownLatch(2);

            WithdrawalCommand command = WithdrawalCommand.builder()
                    .userId(fixture.userId())
                    .bankCode(FIXTURE_BANK_CODE)
                    .accountNo(fixture.accountNo())
                    .amount(1_000L)
                    .idempotencyKey(fixture.idempotencyKey())
                    .build();

            try {
                Future<Void> owner = executor.submit(() ->
                        new TransactionTemplate(transactionManager).execute(status -> {
                            WithdrawalOrderParam param = WithdrawalOrderParam.builder()
                                    .userId(fixture.userId())
                                    .walletId(fixture.walletId())
                                    .linkedAccountId(fixture.accountId())
                                    .amount(1_000L)
                                    .idempotencyKey(fixture.idempotencyKey())
                                    .build();
                            assertEquals(1, withdrawalMapper.insertWithdrawalRequest(param));
                            ownerInserted.countDown();
                            await(releaseOwner);
                            status.setRollbackOnly();
                            return null;
                        })
                );
                assertTrue(ownerInserted.await(5, TimeUnit.SECONDS));

                Future<WithdrawalResult> first = executor.submit(() -> {
                    waitersStarted.countDown();
                    return withdrawalService.withdraw(command);
                });
                Future<WithdrawalResult> second = executor.submit(() -> {
                    waitersStarted.countDown();
                    return withdrawalService.withdraw(command);
                });
                assertTrue(waitersStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> first.get(200, TimeUnit.MILLISECONDS));
                assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));

                releaseOwner.countDown();
                owner.get(5, TimeUnit.SECONDS);

                WithdrawalResult firstResult = first.get(8, TimeUnit.SECONDS);
                WithdrawalResult secondResult = second.get(8, TimeUnit.SECONDS);

                assertTrue(firstResult.isReplayed() ^ secondResult.isReplayed());
                assertEquals(firstResult.getWithdrawalRequestId(), secondResult.getWithdrawalRequestId());
                assertEquals(1, count(jdbcTemplate,
                        "SELECT COUNT(*) FROM withdrawal_requests WHERE idempotency_key = ?",
                        fixture.idempotencyKey()));
                assertEquals(9_000L, value(jdbcTemplate,
                        "SELECT available_balance FROM wallets WHERE id = ?",
                        fixture.walletId()));
            } finally {
                releaseOwner.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                deleteWithdrawalFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    void downstreamLedgerDuplicateRollsBackAllWithdrawalMutations() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            WithdrawalService withdrawalService = context.getBean(WithdrawalService.class);
            WithdrawalFixture fixture = createWithdrawalFixture(jdbcTemplate);
            String ledgerKey = WalletIdempotencyKeys.withdrawal(fixture.idempotencyKey());

            try {
                jdbcTemplate.update(
                        "INSERT INTO wallet_transactions"
                                + " (wallet_id, work_case_id, transaction_type, amount,"
                                + " available_before, available_after, locked_before,"
                                + " locked_after, reference_type, reference_id,"
                                + " idempotency_key)"
                                + " VALUES (?, NULL, 'WITHDRAWAL', 1000, 10000, 9000, 0, 0,"
                                + " 'WITHDRAWAL_REQUEST', 999999, ?)",
                        fixture.walletId(),
                        ledgerKey
                );
                WithdrawalCommand command = WithdrawalCommand.builder()
                        .userId(fixture.userId())
                        .bankCode(FIXTURE_BANK_CODE)
                        .accountNo(fixture.accountNo())
                        .amount(1_000L)
                        .idempotencyKey(fixture.idempotencyKey())
                        .build();

                assertThrows(DuplicateKeyException.class, () -> withdrawalService.withdraw(command));

                // 출금 요청서(withdrawal_requests) 롤백 확인
                assertEquals(0, count(jdbcTemplate,
                        "SELECT COUNT(*) FROM withdrawal_requests WHERE idempotency_key = ?",
                        fixture.idempotencyKey()));
                // 은행 이체 롤백 확인
                assertEquals(0, count(jdbcTemplate,
                        "SELECT COUNT(*) FROM mock_bank_transactions WHERE account_id = ?",
                        fixture.accountId()));
                // 지갑 잔액 롤백 확인 (초기 10000원 유지)
                assertEquals(10_000L, value(jdbcTemplate,
                        "SELECT available_balance FROM wallets WHERE id = ?",
                        fixture.walletId()));
            } finally {
                deleteWithdrawalFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    void snapshotMappingAndMandatoryGatewayContractWorkOnMySql() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            DataSource dataSource = context.getBean(DataSource.class);
            JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
            WithdrawalFixture fixture = createWithdrawalFixture(jdbcTemplate);
            WalletMapper walletMapper = context.getBean(WalletMapper.class);
            BankTransferGateway gateway = context.getBean(BankTransferGateway.class);
            PlatformTransactionManager transactionManager = context.getBean(PlatformTransactionManager.class);
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);

            try {
                assertThrows(
                        IllegalTransactionStateException.class,
                        () -> gateway.preflight(BankAccountPreflightCommand.builder()
                                .accountId(fixture.accountId())
                                .build())
                );

                WalletBalanceSnapshot snapshot = transaction.execute(status -> {
                    WalletBalanceSnapshot locked =
                            walletMapper.getWalletSnapshotForUpdate(fixture.userId());
                    gateway.preflight(BankAccountPreflightCommand.builder()
                            .accountId(fixture.accountId())
                            .build());
                    status.setRollbackOnly();
                    return locked;
                });

                assertNotNull(snapshot);
                assertNotNull(snapshot.getWalletId());
                assertEquals(fixture.userId(), snapshot.getUserId());
                assertNotNull(snapshot.getAvailableBalance());
                assertNotNull(snapshot.getLockedBalance());
            } finally {
                deleteWithdrawalFixture(jdbcTemplate, fixture);
            }
        }
    }

    @Test
    @Timeout(20)
    void concurrentDifferentKeySameWalletWithdrawalsDoNotDeadlock() throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(RootConfig.class)) {
            JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getBean(DataSource.class));
            WithdrawalService withdrawalService = context.getBean(WithdrawalService.class);
            WithdrawalFixture fixture = createWithdrawalFixture(jdbcTemplate);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch start = new CountDownLatch(1);

            // 같은 지갑, 다른 멱등 키로 각각 1,000원 출금
            WithdrawalCommand first = WithdrawalCommand.builder()
                    .userId(fixture.userId())
                    .bankCode(FIXTURE_BANK_CODE)
                    .accountNo(fixture.accountNo())
                    .amount(1_000L)
                    .idempotencyKey(fixture.idempotencyKey() + "-A")
                    .build();
            WithdrawalCommand second = WithdrawalCommand.builder()
                    .userId(fixture.userId())
                    .bankCode(FIXTURE_BANK_CODE)
                    .accountNo(fixture.accountNo())
                    .amount(1_000L)
                    .idempotencyKey(fixture.idempotencyKey() + "-B")
                    .build();

            try {
                Future<WithdrawalResult> f1 = executor.submit(() -> {
                    await(start);
                    return withdrawalService.withdraw(first);
                });
                Future<WithdrawalResult> f2 = executor.submit(() -> {
                    await(start);
                    return withdrawalService.withdraw(second);
                });
                start.countDown();

                // 데드락 없이 둘 다 완료되어야 한다 (재시도로 회복되더라도 예외 전파 없이)
                WithdrawalResult r1 = f1.get(15, TimeUnit.SECONDS);
                WithdrawalResult r2 = f2.get(15, TimeUnit.SECONDS);

                assertEquals("COMPLETED", r1.getStatus());
                assertEquals("COMPLETED", r2.getStatus());
                assertNotEquals(r1.getWithdrawalRequestId(), r2.getWithdrawalRequestId());

                // 서로 다른 출금 2건, 각 1,000원씩 총 2,000원 차감
                assertEquals(2, count(jdbcTemplate,
                        "SELECT COUNT(*) FROM withdrawal_requests WHERE user_id = ?",
                        fixture.userId()));
                assertEquals(8_000L, value(jdbcTemplate,
                        "SELECT available_balance FROM wallets WHERE id = ?",
                        fixture.walletId()));
                assertEquals(2, count(jdbcTemplate,
                        "SELECT COUNT(*) FROM wallet_transactions"
                                + " WHERE wallet_id = ? AND transaction_type = 'WITHDRAWAL'",
                        fixture.walletId()));
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
                deleteWithdrawalFixture(jdbcTemplate, fixture);
            }
        }
    }

    private WithdrawalFixture createWithdrawalFixture(JdbcTemplate jdbcTemplate) {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String loginId = "it_user_" + token;
        String email = loginId + "@example.invalid";
        String fintechUseNumber = "IT-FIN-" + token;
        String accountNumber = "99" + token;
        String idempotencyKey = "IT-WD-" + token;

        jdbcTemplate.update(
                "INSERT INTO users"
                        + " (login_id, email, password_hash, name, role, status)"
                        + " VALUES (?, ?, 'integration-test', '통합 테스트', 'OWNER', 'ACTIVE')",
                loginId, email
        );
        Long userId = jdbcTemplate.queryForObject("SELECT id FROM users WHERE login_id = ?", Long.class, loginId);

        // 출금 테스트를 위해 초기 가용 잔액(10,000원) 부여
        jdbcTemplate.update(
                "INSERT INTO wallets"
                        + " (user_id, currency, available_balance, locked_balance)"
                        + " VALUES (?, 'KRW', 10000, 0)",
                userId
        );
        Long walletId = jdbcTemplate.queryForObject("SELECT id FROM wallets WHERE user_id = ?", Long.class, userId);

        jdbcTemplate.update(
                "INSERT INTO mock_bank_accounts"
                        + " (bank_code, mock_account_number, pin,"
                        + " mock_fintech_use_num, currency, balance,"
                        + " available_amount, status)"
                        + " VALUES ('999', ?, '0000', ?, 'KRW',"
                        + " 1000000, 1000000, 'ACTIVE')",
                accountNumber, fintechUseNumber
        );
        Long accountId = jdbcTemplate.queryForObject(
                "SELECT id FROM mock_bank_accounts WHERE mock_fintech_use_num = ?",
                Long.class,
                fintechUseNumber
        );

        return new WithdrawalFixture(userId, walletId, accountId, accountNumber, idempotencyKey);
    }

    private void deleteWithdrawalFixture(JdbcTemplate jdbcTemplate, WithdrawalFixture fixture) {
        jdbcTemplate.update("DELETE FROM wallet_transactions WHERE wallet_id = ?", fixture.walletId());
        jdbcTemplate.update("DELETE FROM withdrawal_requests WHERE user_id = ?", fixture.userId());
        jdbcTemplate.update("DELETE FROM mock_bank_transactions WHERE account_id = ?", fixture.accountId());
        jdbcTemplate.update("DELETE FROM mock_bank_accounts WHERE id = ?", fixture.accountId());
        jdbcTemplate.update("DELETE FROM wallets WHERE id = ?", fixture.walletId());
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", fixture.userId());
    }

    private int count(JdbcTemplate jdbcTemplate, String sql, Object argument) {
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, argument);
        return count == null ? 0 : count;
    }

    private long value(JdbcTemplate jdbcTemplate, String sql, Object argument) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, argument);
        if (value == null) {
            throw new IllegalStateException("통합 테스트 잔액을 조회할 수 없습니다.");
        }
        return value;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시성 테스트 대기 시간이 초과되었습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시성 테스트가 중단되었습니다.", e);
        }
    }

    private static final String FIXTURE_BANK_CODE = "999";

    private record WithdrawalFixture(
            Long userId,
            Long walletId,
            Long accountId,
            String accountNo,
            String idempotencyKey) {
    }
}
