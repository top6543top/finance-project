package com.trading.account.domain.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.common.exception.GlobalExceptionHandler;
import com.trading.account.domain.account.dto.AccountBalanceResDto;
import com.trading.account.domain.account.dto.AccountCreateResDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class AccountControllerTest {

    @Mock
    private AccountService accountService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        mockMvc = standaloneSetup(new AccountController(accountService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private static final Authentication AUTH = new UsernamePasswordAuthenticationToken(1L, null);

    @Test
    void createAccount_valid_returns201() throws Exception {
        when(accountService.createAccount(any()))
                .thenReturn(new AccountCreateResDto(1L, "123-456-7890", BigDecimal.ZERO, 1L, LocalDateTime.now()));

        mockMvc.perform(post("/api/accounts").principal(AUTH))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accountNumber").value("123-456-7890"));
    }

    @Test
    void getBalance_found_returns200() throws Exception {
        when(accountService.getBalance("123-456-7890", 1L))
                .thenReturn(new AccountBalanceResDto("123-456-7890", BigDecimal.TEN));

        mockMvc.perform(get("/api/accounts/123-456-7890").principal(AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(10));
    }

    @Test
    void getBalance_notFound_returns404() throws Exception {
        when(accountService.getBalance("000", 1L))
                .thenThrow(new CustomException(ErrorCode.ACCOUNT_NOT_FOUND));

        mockMvc.perform(get("/api/accounts/000").principal(AUTH))
                .andExpect(status().isNotFound());
    }
}
