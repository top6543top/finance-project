package com.trading.account.domain.account;

// Luhn(모드10) 체크디지트 — 신용카드 번호 검증과 같은 알고리즘.
// 계좌번호 오타를 컨트롤러 진입 시점(DB 조회 전)에 걸러내기 위함 (IS-21)
public final class AccountNumberValidator {

    private AccountNumberValidator() {
    }

    public static String appendCheckDigit(String digitsOnly) {
        int checkDigit = (10 - luhnSum(digitsOnly, true) % 10) % 10;
        return digitsOnly + checkDigit;
    }

    // 본문 10자리(3+3+4) + 체크디지트 1자리 = 11자리. 실제 계좌번호 형식과 다른 길이는
    // Luhn 합이 우연히 맞아떨어지더라도 무조건 거부한다.
    private static final int ACCOUNT_NUMBER_LENGTH = 11;

    public static boolean isValid(String accountNumber) {
        if (accountNumber == null) {
            return false;
        }
        String digits = accountNumber.replace("-", "");
        // Character.isDigit()는 전각 숫자 등 유니코드 숫자도 true를 반환하는데,
        // luhnSum()의 charAt(i) - '0' 연산은 ASCII '0'~'9'에서만 올바르므로 범위를 명시적으로 제한한다.
        if (digits.length() != ACCOUNT_NUMBER_LENGTH || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            return false;
        }
        return luhnSum(digits, false) % 10 == 0;
    }

    private static int luhnSum(String digits, boolean doubleFirst) {
        int sum = 0;
        boolean doubleDigit = doubleFirst;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleDigit) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleDigit = !doubleDigit;
        }
        return sum;
    }
}
