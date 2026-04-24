package com.trading.account.common.exception;

public class InvalidValueException extends BusinessException {

    public InvalidValueException() {
        super(ErrorCode.INVALID_INPUT_VALUE);
    }

    public InvalidValueException(String message) {
        super(ErrorCode.INVALID_INPUT_VALUE, message);
    }
}
