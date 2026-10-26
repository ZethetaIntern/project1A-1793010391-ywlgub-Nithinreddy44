package com.payscale.httpe;

import com.payscale.httpe.domain.Account;
import com.payscale.httpe.repository.AccountRepository;
import com.payscale.httpe.service.AccountService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
public class ConcurrencyWithdrawalTest {

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    @DisplayName("Mandatory Scenario 1: Two simultaneous withdrawals competing for the same balance")
    void testConcurrentWithdrawalsCompetingForBalance() throws InterruptedException {
        // Setup: Account with initial balance of 1,000 INR
        UUID userId = UUID.randomUUID();
        BigDecimal initialBalance = new BigDecimal("1000.0000");
        Account account = accountService.createAccount(userId, "SAVINGS", "INR", initialBalance, "BASIC");
        UUID accountId = account.getAccountId();

        // Two competing withdrawal tasks attempting to withdraw 800 INR and 600 INR (Sum = 1,400 > 1,000)
        BigDecimal withdrawA = new BigDecimal("800.0000");
        BigDecimal withdrawB = new BigDecimal("600.0000");

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                boolean ok = accountService.debitWithOcc(accountId, withdrawA, 3);
                if (ok) successCount.incrementAndGet();
                else failureCount.incrementAndGet();
            } catch (Exception e) {
                failureCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                boolean ok = accountService.debitWithOcc(accountId, withdrawB, 3);
                if (ok) successCount.incrementAndGet();
                else failureCount.incrementAndGet();
            } catch (Exception e) {
                failureCount.incrementAndGet();
            } finally {
                doneLatch.countDown();
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Fire both simultaneous withdrawals
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Verify: Exactly one transaction succeeded, and the second failed cleanly
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);

        // Verify: Account balance NEVER dropped below zero (Double-Spend Prevented)
        Account updatedAccount = accountRepository.findById(accountId).orElseThrow();
        assertThat(updatedAccount.getAvailableBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        // Balance must be either 200.0000 (if 800 succeeded) or 400.0000 (if 600 succeeded)
        assertThat(updatedAccount.getAvailableBalance())
            .isIn(new BigDecimal("200.0000"), new BigDecimal("400.0000"));
    }
}
