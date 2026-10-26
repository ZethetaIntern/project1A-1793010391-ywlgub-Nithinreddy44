package com.payscale.httpe.repository;

import com.payscale.httpe.domain.TransactionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionEventRepository extends JpaRepository<TransactionEvent, UUID> {
    List<TransactionEvent> findByTransactionIdOrderByCreatedAtAsc(UUID transactionId);
}
