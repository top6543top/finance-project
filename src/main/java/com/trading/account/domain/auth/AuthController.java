package com.trading.account.domain.auth;

import com.trading.account.common.response.ApiResponse;
import com.trading.account.domain.auth.dto.LoginReqDto;
import com.trading.account.domain.auth.dto.LoginResDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResDto>> login(@Valid @RequestBody LoginReqDto request) {
        LoginResDto response = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
