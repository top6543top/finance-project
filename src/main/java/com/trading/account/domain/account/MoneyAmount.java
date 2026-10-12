package com.trading.account.domain.account;

import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;

import java.math.BigDecimal;

/** This simulator has one currency with two fractional digits; it never rounds customer input. */
public final class MoneyAmount {
    public static final BigDecimal MAX = new BigDecimal("99999999999999999.99");

    private MoneyAmount() {}

    public static BigDecimal requireValid(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 || amount.compareTo(MAX) > 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT_VALUE);
        }
        return amount.setScale(2);
    }
}
