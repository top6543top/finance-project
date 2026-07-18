package com.trading.account.domain.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.common.exception.GlobalExceptionHandler;
import com.trading.account.domain.transaction.dto.TransactionResDto;
import com.trading.account.domain.transaction.dto.TransferResDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class TransactionControllerTest {

    @Mock
    private TransactionService transactionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = standaloneSetup(new TransactionController(transactionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    @Test
    void deposit_valid_returns200() throws Exception {
        when(transactionService.deposit(anyString(), any()))
                .thenReturn(new TransactionResDto("123-456-7890", BigDecimal.valueOf(1500), BigDecimal.valueOf(500),
                        TransactionType.DEPOSIT, LocalDateTime.now()));

        mockMvc.perform(post("/api/accounts/123-456-7890/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(1500));
    }

    @Test
    void deposit_negativeAmount_returns400() throws Exception {
        mockMvc.perform(post("/api/accounts/123-456-7890/deposit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withdraw_insufficientBalance_returns409() throws Exception {
        when(transactionService.withdraw(anyString(), any()))
                .thenThrow(new CustomException(ErrorCode.INSUFFICIENT_BALANCE));

        mockMvc.perform(post("/api/accounts/123-456-7890/withdraw")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":999999}"))
                .andExpect(status().isConflict());
    }

    @Test
    void transfer_valid_returns200() throws Exception {
        when(transactionService.transfer(any()))
                .thenReturn(new TransferResDto("111-111-1111", "222-222-2222", BigDecimal.valueOf(100), LocalDateTime.now()));

        mockMvc.perform(post("/api/transfer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountNumber\":\"111-111-1111\",\"toAccountNumber\":\"222-222-2222\",\"amount\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toAccountNumber").value("222-222-2222"));
    }
}
