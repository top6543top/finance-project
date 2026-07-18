package com.trading.account.domain.transaction;

import com.trading.account.domain.account.Account;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TransactionHistoryRepository extends JpaRepository<TransactionHistory, Long> {

    // fromAccount/toAccount를 fetch join으로 함께 가져와 DTO 변환 시 N+1 방지
    @Query("SELECT t FROM TransactionHistory t "
            + "LEFT JOIN FETCH t.fromAccount "
            + "LEFT JOIN FETCH t.toAccount "
            + "WHERE t.fromAccount = :account OR t.toAccount = :account "
            + "ORDER BY t.createdAt DESC")
    Page<TransactionHistory> findByAccount(@Param("account") Account account, Pageable pageable);
}
