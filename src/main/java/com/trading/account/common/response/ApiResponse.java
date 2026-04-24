package com.trading.account.common.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.trading.account.common.exception.ErrorCode;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApiResponse<T> {

    private boolean success;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private T data;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ErrorResponse error;

    private ApiResponse(T data) {
        this.success = true;
        this.data = data;
    }

    private ApiResponse(ErrorResponse error) {
        this.success = false;
        this.error = error;
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data);
    }

    public static <T> ApiResponse<T> success() {
        return new ApiResponse<>((T) null);
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode) {
        return new ApiResponse<>(ErrorResponse.of(errorCode));
    }

    public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message) {
        return new ApiResponse<>(ErrorResponse.of(errorCode, message));
    }

    public static <T> ApiResponse<T> failure(ErrorResponse errorResponse) {
        return new ApiResponse<>(errorResponse);
    }
}
