package com.trading.account.domain.transaction;

import com.trading.account.common.response.ApiResponse;
import com.trading.account.domain.idempotency.IdempotencyService;
import com.trading.account.domain.transaction.dto.DepositReqDto;
import com.trading.account.domain.transaction.dto.TransactionHistoryResDto;
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
import org.springframework.web.bind.annotation.RestController;

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
        TransactionResDto result = idempotencyService.execute(idempotencyKey, TransactionResDto.class,
                () -> transactionService.deposit(accountNumber, requesterId, request.amount()));
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
        TransactionResDto result = idempotencyService.execute(idempotencyKey, TransactionResDto.class,
                () -> transactionService.withdraw(accountNumber, requesterId, request.amount()));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<TransferResDto>> transfer(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferReqDto request,
            Authentication authentication
    ) {
        Long requesterId = (Long) authentication.getPrincipal();
        TransferResDto result = idempotencyService.execute(idempotencyKey, TransferResDto.class,
                () -> transactionService.transfer(request, requesterId));
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
}
