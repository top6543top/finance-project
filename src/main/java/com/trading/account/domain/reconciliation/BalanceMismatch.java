package com.trading.account.domain.reconciliation;

import java.math.BigDecimal;

// 계좌의 현재 잔고와, 거래 이력을 다시 합산한 값이 어긋난 경우에만 만들어지는 결과.
// 일치하는 계좌는 Processor가 null을 반환해 이 레코드 자체가 생기지 않는다.
public record BalanceMismatch(String accountNumber, BigDecimal actualBalance, BigDecimal expectedBalance) {
}
