package com.trading.account.domain.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record DepositReqDto(
        @NotNull(message = "입금액은 필수입니다.")
        @DecimalMin(value = "0.01", message = "입금액은 0보다 커야 합니다.")
        @Digits(integer = 17, fraction = 2, message = "금액은 정수 17자리, 소수 2자리 이내여야 합니다.")
        BigDecimal amount
) {
}
