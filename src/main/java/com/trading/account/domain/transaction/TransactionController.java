package com.trading.account.domain.transaction;

import com.trading.account.common.response.ApiResponse;
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
            @Valid @RequestBody DepositReqDto request,
            Authentication authentication
    ) {
        Long requesterId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(transactionService.deposit(accountNumber, requesterId, request.amount())));
    }

    @PostMapping("/accounts/{accountNumber}/withdraw")
    public ResponseEntity<ApiResponse<TransactionResDto>> withdraw(
            @PathVariable String accountNumber,
            @Valid @RequestBody WithdrawReqDto request,
            Authentication authentication
    ) {
        Long requesterId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(transactionService.withdraw(accountNumber, requesterId, request.amount())));
    }

    @PostMapping("/transfer")
    public ResponseEntity<ApiResponse<TransferResDto>> transfer(
            @Valid @RequestBody TransferReqDto request,
            Authentication authentication
    ) {
        Long requesterId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(ApiResponse.success(transactionService.transfer(request, requesterId)));
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
