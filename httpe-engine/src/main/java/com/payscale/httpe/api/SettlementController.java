package com.payscale.httpe.api;

import com.payscale.httpe.api.dto.Dtos.*;
import com.payscale.httpe.domain.MerchantSettlement;
import com.payscale.httpe.service.MerchantSettlementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/settlements")
public class SettlementController {

    private final MerchantSettlementService settlementService;

    public SettlementController(MerchantSettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @PostMapping("/batch")
    public ResponseEntity<SettlementBatchResponse> processBatch(@RequestBody BatchSettlementRequest req) {
        MerchantSettlement settlement = settlementService.processBatchSettlement(
            req.batchId(),
            req.settlementPoolAccountId(),
            req.merchantAccountId(),
            req.platformFeeAccountId(),
            req.transactionCount(),
            req.grossAmount(),
            req.mdrFeeRate()
        );

        return ResponseEntity.ok(new SettlementBatchResponse(
            settlement.getSettlementId(),
            settlement.getBatchId(),
            settlement.getTransactionCount(),
            settlement.getGrossAmount(),
            settlement.getFeeAmount(),
            settlement.getNetAmount(),
            settlement.getStatus(),
            settlement.getSettledAt()
        ));
    }
}
