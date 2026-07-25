package com.trading.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.Ordered;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.retry.annotation.EnableRetry;

@EnableJpaAuditing
// order를 @Transactional 어드바이저 기본값(LOWEST_PRECEDENCE)보다 낮게 줘서
// Retry가 Transactional 바깥을 감싸도록 함 -> 재시도할 때마다 새 트랜잭션으로 시작.
// 순서가 반대면 이미 롤백된 트랜잭션 안에서 재시도하게 되어 의미가 없음.
@EnableRetry(order = Ordered.HIGHEST_PRECEDENCE)
@SpringBootApplication
public class AccountApplication {

	public static void main(String[] args) {
		SpringApplication.run(AccountApplication.class, args);
	}

}
