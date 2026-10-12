package com.trading.account.domain.transaction;

import com.trading.account.common.response.ApiResponse;
import com.trading.account.domain.account.MoneyAmount;
import com.trading.account.domain.idempotency.IdempotencyService;
import com.trading.account.domain.transaction.dto.DepositReqDto;
import com.trading.account.domain.transaction.dto.TransactionHistoryResDto;
import com.trading.account.domain.transaction.dto.TransactionHistoryCursorResDto;
import com.trading.account.domain.transaction.dto.TransactionResDto;
import com.trading.account.domain.transaction.dto.TransferReqDto;
import com.trading.account.domain.transaction.dto.TransferResDto;
import com.trading.account.domain.transaction.dto.WithdrawReqDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;
    private final IdempotencyService idempotencyService;

    @PostMapping("/accounts/{accountNumber}/deposit")
    public ResponseEntity<ApiResponse<TransactionResDto>> deposit(
            @PathVariable String accountNumber,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody DepositReqDto request,
            Authentication authentication
    ) {
        Long requesterId = (Long) authentication.getPrincipal();
        TransactionResDto result = idempotencyService.execute(requesterId, idempotencyKey, "DEPOSIT",
                List.of(accountNumber, MoneyAmount.requireValid(request.amount())), TransactionResDto.class,
                claim -> transactionService.deposit(accountNumber, requesterId, request.amount(), claim));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/accounts/{accountNumber}/withdraw")
    public ResponseEntity<ApiResponse<TransactionResDto>> withdraw(
            @PathVariable String accountNumber,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody WithdrawReqDto request,
            Authentication authentication
    ) {
        Long requesterId = (Long) authentication.getPrincipal();
        TransactionResDto result = idempotencyService.execute(requesterId, idempotencyKey, "WITHDRAW",
                List.of(accountNumber, MoneyAmount.requireValid(request.amount())), TransactionResDto.class,
                claim -> transactionService.withdraw(accountNumber, requesterId, request.amount(), claim));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<TransferResDto>> transfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferReqDto request,
            Authentication authentication
    ) {
        Long requesterId = (Long) authentication.getPrincipal();
        TransferResDto result = idempotencyService.execute(requesterId, idempotencyKey, "TRANSFER",
                List.of(request.fromAccountNumber(), request.toAccountNumber(), MoneyAmount.requireValid(request.amount())),
                TransferResDto.class, claim -> transactionService.transfer(request, requesterId, claim));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/accounts/{accountNumber}/transactions")
    public ResponseEntity<ApiResponse<Page<TransactionHistoryResDto>>> getHistory(
            @PathVariable String accountNumber,
            Pageable pageable,
            Authentication authentication
    ) {
        Long requesterId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(transactionService.getHistory(accountNumber, requesterId, pageable)));
    }

    @GetMapping("/accounts/{accountNumber}/transactions/cursor")
    public ResponseEntity<ApiResponse<TransactionHistoryCursorResDto>> getHistoryCursor(
            @PathVariable String accountNumber,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        Long requesterId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(transactionService.getHistoryCursor(accountNumber, requesterId, beforeId, size)));
    }
}
