package com.trading.account.domain.member;

import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.domain.member.dto.MemberCreateReqDto;
import com.trading.account.domain.member.dto.MemberCreateResDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @InjectMocks
    private MemberService memberService;

    @Test
    void createMember_success_returnsCreatedMember() {
        MemberCreateReqDto request = new MemberCreateReqDto("김유현", "yuhyun@example.com", "password123!");
        when(memberRepository.existsByEmail(request.email())).thenReturn(false);
        when(passwordEncoder.encode(request.password())).thenReturn("encoded-password");
        when(memberRepository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MemberCreateResDto response = memberService.createMember(request);

        assertThat(response.name()).isEqualTo("김유현");
        assertThat(response.email()).isEqualTo("yuhyun@example.com");
    }

    @Test
    void createMember_duplicateEmail_throwsCustomException() {
        MemberCreateReqDto request = new MemberCreateReqDto("김유현", "dup@example.com", "password123!");
        when(memberRepository.existsByEmail(request.email())).thenReturn(true);

        CustomException exception = catchThrowableOfType(
                () -> memberService.createMember(request), CustomException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
        verify(memberRepository, never()).save(any());
    }

    @Test
    void createMember_raceConditionOnUniqueConstraint_throwsCustomException() {
        MemberCreateReqDto request = new MemberCreateReqDto("김유현", "dup@example.com", "password123!");
        when(memberRepository.existsByEmail(request.email())).thenReturn(false);
        when(memberRepository.save(any(Member.class)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        CustomException exception = catchThrowableOfType(
                () -> memberService.createMember(request), CustomException.class);

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    }
}
