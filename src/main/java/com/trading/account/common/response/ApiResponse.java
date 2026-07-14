package com.trading.account.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.trading.account.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private boolean success;
    private T data;
    private String code;
    private String message;
    private Map<String, String> errors;

    private ApiResponse(boolean success, T data, String code, String message, Map<String, String> errors) {
        this.success = success;
        this.data = data;
        this.code = code;
        this.message = message;
        this.errors = errors;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null, null, null);
    }

    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode) {
        return new ApiResponse<>(false, null, errorCode.getCode(), errorCode.getMessage(), null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, Map<String, String> errors) {
        return new ApiResponse<>(false, null, errorCode.getCode(), errorCode.getMessage(), errors);
    }
}
