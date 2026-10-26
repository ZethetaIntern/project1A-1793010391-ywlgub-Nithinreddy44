package com.payscale.httpe.service;

import com.payscale.httpe.domain.Account;
import com.payscale.httpe.domain.LedgerEntry;
import com.payscale.httpe.domain.OutboxEvent;
import com.payscale.httpe.domain.Transaction;
import com.payscale.httpe.repository.AccountRepository;
import com.payscale.httpe.repository.LedgerEntryRepository;
import com.payscale.httpe.repository.OutboxEventRepository;
import com.payscale.httpe.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
public class PaymentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessingService.class);

    private final AccountService accountService;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final OutboxEventRepository outboxEventRepository;

    public PaymentProcessingService(
        AccountService accountService,
        AccountRepository accountRepository,
        TransactionRepository transactionRepository,
        LedgerEntryRepository ledgerEntryRepository,
        OutboxEventRepository outboxEventRepository
    ) {
        this.accountService = accountService;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    /**
     * Executes atomic P2P payment with double-entry bookkeeping and outbox emission.
     */
    @Transactional
    public Transaction processP2PPayment(
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        String idempotencyKey,
        UUID sagaId
    ) {
        if (sourceAccountId.equals(destinationAccountId)) {
            throw new IllegalArgumentException("Source and destination accounts must be distinct");
        }

        Account source = accountRepository.findById(sourceAccountId)
            .orElseThrow(() -> new IllegalArgumentException("Sender account not found: " + sourceAccountId));

        Account destination = accountRepository.findById(destinationAccountId)
            .orElseThrow(() -> new IllegalArgumentException("Receiver account not found: " + destinationAccountId));

        // Step 1: Pre-create transaction record in PROCESSING state
        UUID txnId = UUID.randomUUID();
        Transaction txn = new Transaction(
            txnId,
            idempotencyKey,
            "P2P",
            sourceAccountId,
            destinationAccountId,
            amount,
            currency != null ? currency : "INR",
            sagaId != null ? sagaId : UUID.randomUUID()
        );
        txn.setStatus("PROCESSING");
        txn = transactionRepository.save(txn);

        // Step 2: Atomic OCC Debit on Sender
        boolean debitOk = accountService.debitWithOcc(sourceAccountId, amount, 3);
        if (!debitOk) {
            txn.setStatus("FAILED");
            txn.setFailureReason("INSUFFICIENT_FUNDS_OR_CONTENTION");
            txn.setCompletedAt(Instant.now());
            return transactionRepository.save(txn);
        }

        // Step 3: Atomic Credit on Receiver
        boolean creditOk = accountService.credit(destinationAccountId, amount);
        if (!creditOk) {
            // Trigger Compensating Reversal on sender (restore funds within 30s)
            log.error("Receiver credit failed for account {}. Executing compensating reversal on sender {}", destinationAccountId, sourceAccountId);
            accountService.executeCompensatingCredit(sourceAccountId, amount);
            txn.setStatus("REVERSED");
            txn.setFailureReason("RECEIVER_CREDIT_FAILED_REVERSED");
            txn.setCompletedAt(Instant.now());
            
            // Reversal ledger entry
            LedgerEntry reversalEntry = new LedgerEntry(
                UUID.randomUUID(), txnId, sourceAccountId, "REVERSAL", amount, currency,
                source.getAvailableBalance() // Restored
            );
            ledgerEntryRepository.save(reversalEntry);
            return transactionRepository.save(txn);
        }

        // Step 4: Double-Entry Bookkeeping Ledger Entries (DEBIT + CREDIT balance to zero)
        Account updatedSource = accountRepository.findById(sourceAccountId).orElse(source);
        Account updatedDest = accountRepository.findById(destinationAccountId).orElse(destination);

        LedgerEntry debitEntry = new LedgerEntry(
            UUID.randomUUID(), txnId, sourceAccountId, "DEBIT", amount, currency, updatedSource.getAvailableBalance()
        );
        LedgerEntry creditEntry = new LedgerEntry(
            UUID.randomUUID(), txnId, destinationAccountId, "CREDIT", amount, currency, updatedDest.getAvailableBalance()
        );
        ledgerEntryRepository.save(debitEntry);
        ledgerEntryRepository.save(creditEntry);

        // Step 5: Settle Sender Ledger Balance
        accountService.settleSenderLedger(sourceAccountId, amount);

        // Step 6: Mark Transaction COMPLETED
        txn.setStatus("COMPLETED");
        txn.setCompletedAt(Instant.now());
        txn = transactionRepository.save(txn);

        // Step 7: Transactional Outbox Event (Guarantees exactly-once downstream propagation)
        String payloadJson = String.format("{\"transactionId\":\"%s\",\"source\":\"%s\",\"dest\":\"%s\",\"amount\":%s}",
            txnId, sourceAccountId, destinationAccountId, amount);
        OutboxEvent outboxEvent = new OutboxEvent(txnId, "PAYMENT_COMPLETED", payloadJson);
        outboxEventRepository.save(outboxEvent);

        log.info("P2P Payment {} COMPLETED successfully: {} INR from {} to {}", txnId, amount, sourceAccountId, destinationAccountId);
        return txn;
    }
}
