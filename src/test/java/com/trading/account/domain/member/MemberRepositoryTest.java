package com.trading.account.domain.member;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class MemberRepositoryTest {

    @Autowired
    private MemberRepository memberRepository;

    @Test
    void save_persistsMemberWithGeneratedId() {
        Member member = new Member("김유현", "yuhyun@example.com", "password123!");

        Member saved = memberRepository.save(member);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getName()).isEqualTo("김유현");
        assertThat(saved.getEmail()).isEqualTo("yuhyun@example.com");
    }

    @Test
    void save_duplicateEmail_violatesUniqueConstraint() {
        memberRepository.saveAndFlush(new Member("A", "dup@example.com", "password123!"));

        assertThatThrownBy(() ->
                memberRepository.saveAndFlush(new Member("B", "dup@example.com", "password123!")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
