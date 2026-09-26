package com.trading.account.domain.reconciliation;

import java.math.BigDecimal;

// 계좌의 현재 잔고와, 거래 이력을 다시 합산한 값이 어긋난 경우에만 만들어지는 결과.
// 일치하는 계좌는 Processor가 null을 반환해 이 레코드 자체가 생기지 않는다.
// accountId: 마스킹된 계좌번호(뒷4자리)만으론 어느 계좌인지 특정 불가 — PK는 내부 식별자라
// 그 자체로 개인을 드러내지 않아 로그에 남겨도 되는 추적 키.
public record BalanceMismatch(Long accountId, String accountNumber, BigDecimal actualBalance, BigDecimal expectedBalance) {
}
