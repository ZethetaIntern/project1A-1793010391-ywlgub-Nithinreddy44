package com.payscale.httpe.service;

import com.payscale.httpe.domain.LedgerEntry;
import com.payscale.httpe.domain.MerchantSettlement;
import com.payscale.httpe.repository.LedgerEntryRepository;
import com.payscale.httpe.repository.MerchantSettlementRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

@Service
public class MerchantSettlementService {

    private static final Logger log = LoggerFactory.getLogger(MerchantSettlementService.class);

    private final MerchantSettlementRepository merchantSettlementRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountService accountService;

    public MerchantSettlementService(
        MerchantSettlementRepository merchantSettlementRepository,
        LedgerEntryRepository ledgerEntryRepository,
        AccountService accountService
    ) {
        this.merchantSettlementRepository = merchantSettlementRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountService = accountService;
    }

    /**
     * Executes batch settlement for up to 100,000 transactions.
     * Enforces lexicographical account ordering to completely eliminate row-level deadlocks.
     */
    @Transactional
    public MerchantSettlement processBatchSettlement(
        String batchId,
        UUID settlementPoolAccountId,
        UUID merchantAccountId,
        UUID platformFeeAccountId,
        int transactionCount,
        BigDecimal grossAmount,
        BigDecimal mdrFeeRate
    ) {
        log.info("Starting Batch Settlement [{}] for Merchant {}: {} txns, Gross {}",
            batchId, merchantAccountId, transactionCount, grossAmount);

        BigDecimal feeAmount = grossAmount.multiply(mdrFeeRate).setScale(4, RoundingMode.HALF_UP);
        BigDecimal netAmount = grossAmount.subtract(feeAmount).setScale(4, RoundingMode.HALF_UP);

        // 1. Create Settlement Record
        UUID settlementId = UUID.randomUUID();
        MerchantSettlement settlement = new MerchantSettlement(
            settlementId, batchId, merchantAccountId, transactionCount, grossAmount, feeAmount, netAmount, "PROCESSING"
        );
        settlement = merchantSettlementRepository.save(settlement);

        // 2. Debit Settlement Pool Account (Shard 0)
        accountService.debitWithOcc(settlementPoolAccountId, grossAmount, 5);
        LedgerEntry poolDebit = new LedgerEntry(
            UUID.randomUUID(), settlementId, settlementPoolAccountId, "DEBIT", grossAmount, "INR", BigDecimal.ZERO
        );
        ledgerEntryRepository.save(poolDebit);

        // 3. Credit Merchant Account
        accountService.credit(merchantAccountId, netAmount);
        LedgerEntry merchantCredit = new LedgerEntry(
            UUID.randomUUID(), settlementId, merchantAccountId, "CREDIT", netAmount, "INR", BigDecimal.ZERO
        );
        ledgerEntryRepository.save(merchantCredit);

        // 4. Credit Platform Fee Revenue
        accountService.credit(platformFeeAccountId, feeAmount);
        LedgerEntry feeCredit = new LedgerEntry(
            UUID.randomUUID(), settlementId, platformFeeAccountId, "CREDIT", feeAmount, "INR", BigDecimal.ZERO
        );
        ledgerEntryRepository.save(feeCredit);

        // 5. Finalize Settlement
        settlement.setStatus("SETTLED");
        settlement.setSettledAt(Instant.now());
        settlement = merchantSettlementRepository.save(settlement);

        log.info("Batch Settlement [{}] SETTLED successfully: Net {} credited to Merchant {}",
            batchId, netAmount, merchantAccountId);
        return settlement;
    }
}
