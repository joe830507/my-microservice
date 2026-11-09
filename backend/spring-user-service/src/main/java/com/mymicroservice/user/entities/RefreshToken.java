package com.mymicroservice.user.entities;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.annotations.UuidGenerator.Style;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

// 不繼承 CommonEntity：建立後只會被撤銷，不需要 updated_*、created_by 與樂觀鎖
// 撤銷與輪替用條件式 UPDATE（RefreshTokenRepository），不透過實體修改
@Getter
@Setter
@Entity
@Table(schema = "user_service", name = "refresh_token")
public class RefreshToken {

    @Id
    @UuidGenerator(style = Style.VERSION_7)
    @Setter(AccessLevel.NONE)
    private UUID id;

    // 只存 id 不建立關聯：refresh 時需要帶 roles 的 User，另外用 findWithRolesById 查
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    // 原始 token 只在回應中出現一次，DB 只存 SHA-256 雜湊值
    @Column(name = "token_hash", nullable = false, updatable = false)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    // 因輪替而撤銷時指向新的 token；登出、停用、重複使用偵測撤銷的是 null
    // 由 RefreshTokenRepository#rotateIfActive 寫入
    @Column(name = "replaced_by")
    private UUID replacedBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
            return false;
        }
        RefreshToken other = (RefreshToken) o;
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return Hibernate.getClass(this).hashCode();
    }
}
