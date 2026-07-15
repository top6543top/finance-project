package com.trading.account.domain.account.dto;

import java.math.BigDecimal;

public record AccountBalanceResDto(
        String accountNumber,
        BigDecimal balance
) {
}
