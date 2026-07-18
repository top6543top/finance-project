package com.trading.account.domain.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record TransferReqDto(
        @NotBlank(message = "출금 계좌번호는 필수입니다.") String fromAccountNumber,
        @NotBlank(message = "입금 계좌번호는 필수입니다.") String toAccountNumber,
        @NotNull(message = "이체액은 필수입니다.")
        @DecimalMin(value = "0.01", message = "이체액은 0보다 커야 합니다.")
        BigDecimal amount
) {
}
