package com.mymicroservice.user.repositories;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.mymicroservice.user.entities.RefreshToken;

// 撤銷一律用條件式 UPDATE（WHERE revoked_at IS NULL），由 DB 決定併發時誰成功；
// 回傳更新筆數，0 代表已被其他請求搶先撤銷
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    boolean existsByIdAndRevokedAtIsNull(UUID id);

    // 輪替：撤銷舊 token 並記錄換成哪一個新 token（重複使用寬限期用）
    // 同一個 token 併發 refresh 時，只有一個請求能拿到 1
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now, t.replacedBy = :replacedBy "
            + "WHERE t.id = :id AND t.revokedAt IS NULL")
    int rotateIfActive(@Param("id") UUID id, @Param("replacedBy") UUID replacedBy, @Param("now") Instant now);

    // 單純撤銷（不記錄 replaced_by，所以不會有寬限期），測試與之後的管理功能使用
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.id = :id AND t.revokedAt IS NULL")
    int revokeIfActive(@Param("id") UUID id, @Param("now") Instant now);

    // 登出：直接用雜湊值撤銷，不需要先查出來；不寫 replaced_by，所以被登出的 token 不會有寬限期
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.tokenHash = :tokenHash AND t.revokedAt IS NULL")
    int revokeByTokenHashIfActive(@Param("tokenHash") String tokenHash, @Param("now") Instant now);

    // 重複使用偵測、帳號停用時，撤銷該使用者所有仍有效的 token
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revokedAt = :now WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeAllActiveByUserId(@Param("userId") UUID userId, @Param("now") Instant now);
}
