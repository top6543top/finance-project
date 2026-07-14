package com.trading.account.domain.member;

import com.trading.account.common.response.ApiResponse;
import com.trading.account.domain.member.dto.MemberCreateReqDto;
import com.trading.account.domain.member.dto.MemberCreateResDto;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MemberController {

    private final MemberService memberService;

    @PostMapping("/members")
    public ResponseEntity<ApiResponse<MemberCreateResDto>> createMember(
            @Valid @RequestBody MemberCreateReqDto request
    ) {
        MemberCreateResDto response = memberService.createMember(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }
}
