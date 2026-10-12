package com.trading.account.domain.reconciliation;

import java.math.BigDecimal;

// Current-state observation, not a historical daily closing balance.
// Contains sensitive values: persist only to the restricted results table; never log this record.
public record BalanceMismatch(Long accountId, String accountNumber, BigDecimal actualBalance, BigDecimal expectedBalance) {
}
