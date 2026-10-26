package com.payscale.httpe.repository;

import com.payscale.httpe.domain.MerchantSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MerchantSettlementRepository extends JpaRepository<MerchantSettlement, UUID> {
    Optional<MerchantSettlement> findByBatchId(String batchId);
}
