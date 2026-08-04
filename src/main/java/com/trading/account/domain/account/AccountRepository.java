package com.trading.account.domain.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {

    boolean existsByAccountNumber(String accountNumber);

    Optional<Account> findByAccountNumber(String accountNumber);

    // IS-30 실험용: 비관적 락(SELECT ... FOR UPDATE)으로 조회 시점에 바로 X-lock을 잡는다.
    // 낙관적 락(@Version)과 달리 충돌 시 재시도가 아니라 락 대기이므로, 핫스팟(계좌 1개 몰림)에서
    // 실패율 대비 지연시간 트레이드오프를 비교하기 위한 용도. 프로덕션 경로에서는 미사용.
    // 메서드 이름의 "ForUpdate"를 derived query가 프로퍼티로 오인하므로 @Query로 명시.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.accountNumber = :accountNumber")
    Optional<Account> findByAccountNumberForUpdate(@Param("accountNumber") String accountNumber);
}
