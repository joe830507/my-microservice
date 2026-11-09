package com.mymicroservice.user.service.impl;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.user.dtos.me.GetMeResDto;
import com.mymicroservice.user.dtos.users.GetUsersReqDto;
import com.mymicroservice.user.dtos.users.GetUsersResDto;
import com.mymicroservice.user.entities.User;
import com.mymicroservice.user.exception.UserErrorCode;
import com.mymicroservice.user.repositories.RefreshTokenRepository;
import com.mymicroservice.user.repositories.UserRepository;
import com.mymicroservice.user.service.UserService;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;

    // 登出所有裝置：撤銷該使用者所有仍有效的 refresh token
    // 撤銷時不寫 replaced_by；剛輪替掉的舊 token 也因為新 token 已被撤銷而失去寬限期
    // access token 無法撤銷，各裝置會在到期（最多 15 分鐘）後無法再換發
    // 沒有任何 token、或使用者不存在，結果都是「沒有有效 token」，不回錯誤
    @Override
    public void revokeAllSessions(UUID userId) {
        refreshTokenRepository.revokeAllActiveByUserId(userId, Instant.now());
    }

    // 停用的帳號仍回傳資料（status 為 DISABLED）：access token 在到期前依然有效，
    // 擋下停用帳號是登入與之後 refresh 的責任，前端可依 status 顯示提示
    @Override
    @Transactional(readOnly = true)
    public GetMeResDto getMe(UUID userId) {
        // token 有效但使用者已不存在（例如被刪除）時回 404
        return userRepository.findWithRolesById(userId)
                .map(GetMeResDto::from)
                .orElseThrow(() -> new AppException(UserErrorCode.USER_NOT_FOUND));
    }

    // roles 在交易內轉成 DTO 時才載入，由 User.roles 的 @BatchSize 一次撈回整頁
    @Override
    @Transactional(readOnly = true)
    public GetUsersResDto getUsers(GetUsersReqDto getUsersReqDto) {
        Page<User> userPage = userRepository.findAll(toSpecification(getUsersReqDto),
                getUsersReqDto.toPageable());
        return GetUsersResDto.from(userPage);
    }

    // 只組合有傳入的條件，全部未傳入時等同查詢全部
    private Specification<User> toSpecification(GetUsersReqDto getUsersReqDto) {
        Specification<User> specification = Specification.unrestricted();

        if (getUsersReqDto.keyword() != null && !getUsersReqDto.keyword().isBlank()) {
            String pattern = "%" + escapeLike(getUsersReqDto.keyword().strip().toLowerCase(Locale.ROOT)) + "%";
            specification = specification.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("email")), pattern, '\\'),
                    cb.like(cb.lower(root.get("displayName")), pattern, '\\')));
        }
        if (getUsersReqDto.status() != null) {
            specification = specification.and((root, query, cb) -> cb.equal(root.get("status"),
                    getUsersReqDto.status()));
        }
        if (getUsersReqDto.role() != null) {
            // (user_id, role) 是主鍵，每位使用者最多 join 出一筆，不會產生重複資料
            specification = specification.and((root, query, cb) -> cb.equal(root.join("roles"),
                    getUsersReqDto.role()));
        }
        return specification;
    }

    // 跳脫 LIKE 的萬用字元，避免使用者輸入 % 或 _ 時被當成模糊比對
    private String escapeLike(String keyword) {
        return keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
