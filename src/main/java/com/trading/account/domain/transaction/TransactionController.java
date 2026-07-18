package com.trading.account.domain.transaction;

import com.trading.account.common.response.ApiResponse;
import com.trading.account.domain.transaction.dto.DepositReqDto;
import com.trading.account.domain.transaction.dto.TransactionResDto;
import com.trading.account.domain.transaction.dto.WithdrawReqDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    @PostMapping("/accounts/{accountNumber}/deposit")
    public ResponseEntity<ApiResponse<TransactionResDto>> deposit(
            @PathVariable String accountNumber,
            @Valid @RequestBody DepositReqDto request
    ) {
        return ResponseEntity.ok(ApiResponse.success(transactionService.deposit(accountNumber, request.amount())));
    }

    @PostMapping("/accounts/{accountNumber}/withdraw")
    public ResponseEntity<ApiResponse<TransactionResDto>> withdraw(
            @PathVariable String accountNumber,
            @Valid @RequestBody WithdrawReqDto request
    ) {
        return ResponseEntity.ok(ApiResponse.success(transactionService.withdraw(accountNumber, request.amount())));
    }
}
