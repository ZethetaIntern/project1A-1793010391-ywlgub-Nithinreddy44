package com.payscale.httpe.repository;

import com.payscale.httpe.domain.NotificationLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {
    List<NotificationLog> findByStatus(String status, Pageable pageable);
    List<NotificationLog> findByTransactionId(UUID transactionId);
}
