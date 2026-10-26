package com.payscale.httpe.service;

import com.payscale.httpe.domain.Account;
import com.payscale.httpe.repository.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;

    public AccountService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Transactional
    public Account createAccount(UUID userId, String accountType, String currency, BigDecimal initialBalance, String tier) {
        UUID accountId = UUID.randomUUID();
        Account account = new Account(
            accountId,
            userId,
            accountType,
            currency != null ? currency : "INR",
            initialBalance != null ? initialBalance : BigDecimal.ZERO,
            tier != null ? tier : "BASIC",
            Math.abs(accountId.hashCode()) % 4
        );
        return accountRepository.save(account);
    }

    @Transactional(readOnly = true)
    public Optional<Account> getAccount(UUID accountId) {
        return accountRepository.findById(accountId);
    }

    /**
     * Executes atomic OCC debit with bounded adaptive retry loop (eliminates database row deadlocks).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean debitWithOcc(UUID accountId, BigDecimal amount, int maxRetries) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Debit amount must be strictly positive");
        }

        int attempts = 0;
        while (attempts < maxRetries) {
            attempts++;

            Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountId));

            if (!"ACTIVE".equalsIgnoreCase(account.getStatus())) {
                throw new IllegalStateException("Account is not active: " + account.getStatus());
            }

            if (account.getAvailableBalance().compareTo(amount) < 0) {
                log.warn("Debit rejected for account {}: available balance {} < requested {}",
                    accountId, account.getAvailableBalance(), amount);
                return false; // Insufficient funds
            }

            // Atomic CAS execution: checks version and balance in single SQL predicate
            int updated = accountRepository.executeAtomicCasDebit(
                accountId,
                amount,
                account.getVersion(),
                Instant.now()
            );

            if (updated == 1) {
                log.debug("OCC debit succeeded on attempt {} for account {}", attempts, accountId);
                return true;
            }

            // Collision detected -> Backoff with randomized full jitter (2ms to 10ms)
            long sleepMs = Math.min(15, 2L * (1L << (attempts - 1))) + ThreadLocalRandom.current().nextInt(1, 5);
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        log.warn("OCC debit retries exhausted ({}) for account {}", maxRetries, accountId);
        return false;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean credit(UUID accountId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Credit amount must be strictly positive");
        }
        int updated = accountRepository.executeAtomicCredit(accountId, amount, Instant.now());
        return updated == 1;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void settleSenderLedger(UUID accountId, BigDecimal amount) {
        accountRepository.executeLedgerSettleDebit(accountId, amount, Instant.now());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void executeCompensatingCredit(UUID accountId, BigDecimal amount) {
        log.info("Executing compensating credit of {} to restore sender account {}", amount, accountId);
        accountRepository.executeCompensatingCredit(accountId, amount, Instant.now());
    }
}
