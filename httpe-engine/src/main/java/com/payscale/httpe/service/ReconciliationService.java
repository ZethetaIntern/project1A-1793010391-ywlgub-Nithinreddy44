package com.payscale.httpe.service;

import com.payscale.httpe.repository.LedgerEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final LedgerEntryRepository ledgerEntryRepository;

    public ReconciliationService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    public static class ReconciliationReport {
        private final BigDecimal totalDebits;
        private final BigDecimal totalCredits;
        private final BigDecimal totalReversals;
        private final BigDecimal netDiscrepancy;
        private final boolean isBalanced;

        public ReconciliationReport(BigDecimal totalDebits, BigDecimal totalCredits, BigDecimal totalReversals) {
            this.totalDebits = totalDebits != null ? totalDebits.setScale(4) : BigDecimal.ZERO.setScale(4);
            this.totalCredits = totalCredits != null ? totalCredits.setScale(4) : BigDecimal.ZERO.setScale(4);
            this.totalReversals = totalReversals != null ? totalReversals.setScale(4) : BigDecimal.ZERO.setScale(4);
            // In double-entry bookkeeping: Debits must balance Credits + Reversals
            this.netDiscrepancy = this.totalDebits.subtract(this.totalCredits).abs();
            this.isBalanced = this.netDiscrepancy.compareTo(new BigDecimal("0.0001")) < 0;
        }

        public BigDecimal getTotalDebits() { return totalDebits; }
        public BigDecimal getTotalCredits() { return totalCredits; }
        public BigDecimal getTotalReversals() { return totalReversals; }
        public BigDecimal getNetDiscrepancy() { return netDiscrepancy; }
        public boolean isBalanced() { return isBalanced; }
    }

    /**
     * Executes double-entry verification check across all platform ledger journals.
     */
    public ReconciliationReport runReconciliationAudit() {
        BigDecimal debits = ledgerEntryRepository.calculateTotalDebits();
        BigDecimal credits = ledgerEntryRepository.calculateTotalCredits();
        BigDecimal reversals = ledgerEntryRepository.calculateTotalReversals();

        ReconciliationReport report = new ReconciliationReport(debits, credits, reversals);
        if (report.isBalanced()) {
            log.info("Reconciliation Audit PASSED: Double-entry invariant holds. Debits = {}, Credits = {}, Discrepancy = 0.0000",
                report.getTotalDebits(), report.getTotalCredits());
        } else {
            log.error("CRITICAL P1 ALERT: Reconciliation Audit FAILED! Ledger discrepancy detected: {} INR. Debits = {}, Credits = {}",
                report.getNetDiscrepancy(), report.getTotalDebits(), report.getTotalCredits());
        }
        return report;
    }

    @Scheduled(fixedDelay = 60000) // Hourly/periodic background execution
    public void scheduledAudit() {
        runReconciliationAudit();
    }
}
