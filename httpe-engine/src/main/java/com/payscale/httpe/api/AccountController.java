package com.payscale.httpe.api;

import com.payscale.httpe.api.dto.Dtos.*;
import com.payscale.httpe.domain.Account;
import com.payscale.httpe.service.AccountService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@RequestBody CreateAccountRequest req) {
        Account account = accountService.createAccount(
            req.userId(),
            req.accountType(),
            req.currency(),
            req.initialBalance(),
            req.tier()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(account));
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<AccountResponse> getAccount(@PathVariable UUID accountId) {
        return accountService.getAccount(accountId)
            .map(acc -> ResponseEntity.ok(toResponse(acc)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private AccountResponse toResponse(Account a) {
        return new AccountResponse(
            a.getAccountId(),
            a.getUserId(),
            a.getAccountType(),
            a.getCurrency(),
            a.getAvailableBalance(),
            a.getLedgerBalance(),
            a.getVersion(),
            a.getStatus(),
            a.getTier(),
            a.getCreatedAt()
        );
    }
}
