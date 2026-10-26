package com.payscale.httpe.repository;

import com.payscale.httpe.domain.Transaction;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    List<Transaction> findBySagaId(UUID sagaId);

    @Query("SELECT t FROM Transaction t WHERE " +
           "(t.sourceAccountId = :accountId OR t.destinationAccountId = :accountId) " +
           "ORDER BY t.createdAt DESC")
    List<Transaction> findLatestByAccount(@Param("accountId") UUID accountId, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE " +
           "(t.sourceAccountId = :accountId OR t.destinationAccountId = :accountId) " +
           "AND t.createdAt < :cursorTime " +
           "ORDER BY t.createdAt DESC")
    List<Transaction> findByAccountCursor(@Param("accountId") UUID accountId,
                                         @Param("cursorTime") Instant cursorTime,
                                         Pageable pageable);

    List<Transaction> findByStatus(String status);
}
