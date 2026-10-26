package com.payscale.httpe.api;

import com.payscale.httpe.api.dto.Dtos.*;
import com.payscale.httpe.domain.Transaction;
import com.payscale.httpe.repository.TransactionRepository;
import com.payscale.httpe.service.TransactionOrchestrator;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
public class TransactionController {

    private final TransactionRepository transactionRepository;
    private final TransactionOrchestrator orchestrator;

    public TransactionController(TransactionRepository transactionRepository, TransactionOrchestrator orchestrator) {
        this.transactionRepository = transactionRepository;
        this.orchestrator = orchestrator;
    }

    @GetMapping("/transactions/{transactionId}")
    public ResponseEntity<TransactionResponse> getTransaction(@PathVariable UUID transactionId) {
        return transactionRepository.findById(transactionId)
            .map(t -> ResponseEntity.ok(toResponse(t)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/accounts/{accountId}/transactions")
    public ResponseEntity<PaginatedTransactionsResponse> getAccountTransactions(
        @PathVariable UUID accountId,
        @RequestParam(value = "cursor", required = false) String cursor,
        @RequestParam(value = "limit", defaultValue = "20") int limit
    ) {
        int boundedLimit = Math.min(Math.max(limit, 1), 100);
        List<Transaction> txns;

        if (cursor != null && !cursor.isBlank()) {
            Instant cursorTime = decodeCursor(cursor);
            txns = transactionRepository.findByAccountCursor(accountId, cursorTime, PageRequest.of(0, boundedLimit));
        } else {
            txns = transactionRepository.findLatestByAccount(accountId, PageRequest.of(0, boundedLimit));
        }

        List<TransactionResponse> responses = txns.stream().map(this::toResponse).collect(Collectors.toList());
        String nextCursor = null;
        boolean hasMore = txns.size() == boundedLimit;

        if (!txns.isEmpty() && hasMore) {
            Transaction last = txns.get(txns.size() - 1);
            nextCursor = encodeCursor(last.getCreatedAt());
        }

        return ResponseEntity.ok(new PaginatedTransactionsResponse(responses, nextCursor, hasMore));
    }

    @PostMapping("/transactions/{transactionId}/reversals")
    public ResponseEntity<TransactionResponse> requestReversal(
        @PathVariable UUID transactionId,
        @RequestBody ReversalRequest req
    ) {
        Transaction reversedTxn = orchestrator.executeCompensatingReversal(transactionId, req.reason());
        return ResponseEntity.ok(toResponse(reversedTxn));
    }

    private String encodeCursor(Instant instant) {
        return Base64.getEncoder().encodeToString(instant.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Instant decodeCursor(String cursor) {
        try {
            byte[] decoded = Base64.getDecoder().decode(cursor);
            return Instant.parse(new String(decoded, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private TransactionResponse toResponse(Transaction t) {
        return new TransactionResponse(
            t.getTransactionId(),
            t.getIdempotencyKey(),
            t.getTransactionType(),
            t.getSourceAccountId(),
            t.getDestinationAccountId(),
            t.getAmount(),
            t.getCurrency(),
            t.getStatus(),
            t.getSagaId(),
            t.getFailureReason(),
            t.getCreatedAt(),
            t.getCompletedAt()
        );
    }
}
