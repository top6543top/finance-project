package com.trading.account.domain.transaction;

import com.trading.account.domain.account.Account;
import com.trading.account.domain.account.MoneyAmount;
import com.trading.account.domain.idempotency.IdempotencyKey;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(indexes = {
        @Index(name = "idx_history_from_id", columnList = "from_account_id,id"),
        @Index(name = "idx_history_to_id", columnList = "to_account_id,id")
})
@Getter
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TransactionHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_account_id")
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id")
    private Account toAccount;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private TransactionType type;

    // Nullable only for pre-migration history whose actor cannot be safely reconstructed.
    @Column(name = "actor_id")
    private Long actorId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "idempotency_key_id")
    private IdempotencyKey idempotencyKey;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public TransactionHistory(Account from, Account to, BigDecimal amount, TransactionType type) {
        this(from, to, amount, type, (from != null ? from : to).getMember().getId(), null);
    }

    public TransactionHistory(Account from, Account to, BigDecimal amount, TransactionType type,
                              Long actorId, IdempotencyKey idempotencyKey) {
        this.fromAccount = from;
        this.toAccount = to;
        this.amount = MoneyAmount.requireValid(amount);
        this.type = type;
        this.actorId = actorId;
        this.idempotencyKey = idempotencyKey;
    }

    public Long getIdempotencyId() {
        return idempotencyKey == null ? null : idempotencyKey.getId();
    }
}
