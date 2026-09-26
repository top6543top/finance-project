package com.trading.account.domain.transaction;

import com.trading.account.domain.account.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public interface TransactionHistoryRepository extends JpaRepository<TransactionHistory, Long> {

    // fromAccount/toAccount를 fetch join으로 함께 가져와 DTO 변환 시 N+1 방지
    @Query("SELECT t FROM TransactionHistory t "
            + "LEFT JOIN FETCH t.fromAccount "
            + "LEFT JOIN FETCH t.toAccount "
            + "WHERE t.fromAccount = :account OR t.toAccount = :account "
            + "ORDER BY t.createdAt DESC")
    Page<TransactionHistory> findByAccount(@Param("account") Account account, Pageable pageable);

    // 정산 배치(IS-28)용: 입금/이체수신(+)과 출금/이체송금(-)을 한 쿼리로 상계해 "이 계좌가
    // 거래 이력상 가져야 할 잔고"를 계산. DEPOSIT/WITHDRAW/TRANSFER 전부 이 부호 규칙 하나로
    // 커버되므로(TransactionType별 분기 불필요) type 조건 없이 방향(from/to)만 본다.
    @Query("SELECT COALESCE(SUM(CASE WHEN t.toAccount.id = :accountId THEN t.amount ELSE 0 END), 0) "
            + "- COALESCE(SUM(CASE WHEN t.fromAccount.id = :accountId THEN t.amount ELSE 0 END), 0) "
            + "FROM TransactionHistory t "
            + "WHERE t.fromAccount.id = :accountId OR t.toAccount.id = :accountId")
    BigDecimal calculateNetAmount(@Param("accountId") Long accountId);
}
