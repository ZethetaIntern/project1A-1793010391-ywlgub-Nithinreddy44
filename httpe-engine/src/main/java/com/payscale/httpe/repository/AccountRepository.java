package com.payscale.httpe.repository;

import com.payscale.httpe.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByUserId(UUID userId);

    /**
     * Executes atomic Compare-And-Swap (CAS) debit predicate.
     * Guarantees zero negative balances and increments OCC version in a single atomic SQL update.
     * Returns 1 if successful, 0 if collision or insufficient balance.
     */
    @Modifying
    @Query("UPDATE Account a SET a.availableBalance = a.availableBalance - :amount, " +
           "a.version = a.version + 1, a.updatedAt = :now " +
           "WHERE a.accountId = :accountId AND a.version = :expectedVersion AND a.availableBalance >= :amount")
    int executeAtomicCasDebit(@Param("accountId") UUID accountId,
                              @Param("amount") BigDecimal amount,
                              @Param("expectedVersion") Long expectedVersion,
                              @Param("now") Instant now);

    /**
     * Executes atomic credit and increments OCC version.
     */
    @Modifying
    @Query("UPDATE Account a SET a.availableBalance = a.availableBalance + :amount, " +
           "a.ledgerBalance = a.ledgerBalance + :amount, " +
           "a.version = a.version + 1, a.updatedAt = :now " +
           "WHERE a.accountId = :accountId")
    int executeAtomicCredit(@Param("accountId") UUID accountId,
                            @Param("amount") BigDecimal amount,
                            @Param("now") Instant now);

    /**
     * Settles the ledger balance for sender upon confirmed transaction completion.
     */
    @Modifying
    @Query("UPDATE Account a SET a.ledgerBalance = a.ledgerBalance - :amount, a.updatedAt = :now " +
           "WHERE a.accountId = :accountId")
    int executeLedgerSettleDebit(@Param("accountId") UUID accountId,
                                 @Param("amount") BigDecimal amount,
                                 @Param("now") Instant now);

    /**
     * Compensating reversal: restores available balance.
     */
    @Modifying
    @Query("UPDATE Account a SET a.availableBalance = a.availableBalance + :amount, " +
           "a.version = a.version + 1, a.updatedAt = :now " +
           "WHERE a.accountId = :accountId")
    int executeCompensatingCredit(@Param("accountId") UUID accountId,
                                 @Param("amount") BigDecimal amount,
                                 @Param("now") Instant now);
}
