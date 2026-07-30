package com.trading.account.domain.member;

import com.trading.account.common.exception.CustomException;
import com.trading.account.common.exception.ErrorCode;
import com.trading.account.domain.member.dto.MemberCreateReqDto;
import com.trading.account.domain.member.dto.MemberCreateResDto;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;

    public MemberCreateResDto createMember(MemberCreateReqDto request) {
        if (memberRepository.existsByEmail(request.email())) {
            throw new CustomException(ErrorCode.DUPLICATE_RESOURCE);
        }

        Member member;
        try {
            member = memberRepository.save(new Member(
                    request.name(), request.email(), passwordEncoder.encode(request.password())));
        } catch (DataIntegrityViolationException e) {
            throw new CustomException(ErrorCode.DUPLICATE_RESOURCE);
        }

        return new MemberCreateResDto(member.getId(), member.getName(), member.getEmail(), member.getCreatedAt());
    }
}
