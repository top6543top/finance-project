package com.trading.account.domain.idempotency;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_actor_key", columnNames = {"actor_id", "idempotency_key"}),
        indexes = @Index(name = "idx_idempotency_legacy", columnList = "actor_id,legacy_key"))
@Getter
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyKey {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "idempotency_key", nullable = false, length = 100,
            columnDefinition = "varchar(100) collate utf8mb4_0900_bin")
    private String key;

    // Upgrade migrations preserve the original key collation here, including legacy case/accent aliases.
    @Column(name = "legacy_key", length = 100, columnDefinition = "varchar(100) collate utf8mb4_0900_ai_ci")
    private String legacyKey;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "operation", nullable = false, length = 20)
    private String operation;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IdempotencyStatus status;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    public IdempotencyKey(Long actorId, String key, String operation, String requestFingerprint) {
        this.actorId = actorId;
        this.key = key;
        this.legacyKey = Long.valueOf(0).equals(actorId) ? key : null;
        this.operation = operation;
        this.requestFingerprint = requestFingerprint;
        this.status = IdempotencyStatus.IN_PROGRESS;
    }

    public void complete(String responseBody) {
        this.status = IdempotencyStatus.COMPLETED;
        this.responseBody = responseBody;
    }
}
