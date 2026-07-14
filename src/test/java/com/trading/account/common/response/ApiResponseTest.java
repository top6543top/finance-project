package com.trading.account.common.response;

import com.trading.account.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    void success_withData_wrapsDataAndSetsSuccessTrue() {
        ApiResponse<String> response = ApiResponse.success("hello");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo("hello");
        assertThat(response.getCode()).isNull();
    }

    @Test
    void success_withoutData_setsDataNull() {
        ApiResponse<Void> response = ApiResponse.success();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getData()).isNull();
    }

    @Test
    void error_setsSuccessFalseAndErrorCodeFields() {
        ApiResponse<Void> response = ApiResponse.error(ErrorCode.ENTITY_NOT_FOUND);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getData()).isNull();
        assertThat(response.getCode()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND.getCode());
        assertThat(response.getMessage()).isEqualTo(ErrorCode.ENTITY_NOT_FOUND.getMessage());
    }

    @Test
    void error_withFieldErrors_includesErrorsMap() {
        ApiResponse<Void> response = ApiResponse.error(
                ErrorCode.INVALID_INPUT_VALUE, Map.of("email", "must not be blank"));

        assertThat(response.getErrors()).containsEntry("email", "must not be blank");
    }
}
