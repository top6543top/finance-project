package com.trading.account.common.logging;

// 계좌번호 전체를 로그에 그대로 찍지 않기 위한 공용 마스킹 — TransactionService, 정산 배치 등
// 계좌번호를 로그로 남기는 모든 지점에서 재사용해 마스킹 누락을 방지한다.
public final class AccountNumberMasker {

    private AccountNumberMasker() {
    }

    public static String mask(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return "***";
        }
        return "***-***-" + accountNumber.substring(accountNumber.length() - 4);
    }
}
