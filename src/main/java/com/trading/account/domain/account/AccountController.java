package com.trading.account.domain.account;

import com.trading.account.common.response.ApiResponse;
import com.trading.account.domain.account.dto.AccountBalanceResDto;
import com.trading.account.domain.account.dto.AccountCreateReqDto;
import com.trading.account.domain.account.dto.AccountCreateResDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping("/accounts")
    public ResponseEntity<ApiResponse<AccountCreateResDto>> createAccount(
            @Valid @RequestBody AccountCreateReqDto request
    ) {
        AccountCreateResDto response = accountService.createAccount(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping("/accounts/{accountNumber}")
    public ResponseEntity<ApiResponse<AccountBalanceResDto>> getBalance(
            @PathVariable String accountNumber
    ) {
        return ResponseEntity.ok(ApiResponse.success(accountService.getBalance(accountNumber)));
    }
}
