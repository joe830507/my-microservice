package com.mymicroservice.user.service.impl;

import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.common.events.OutboxEventWriter;
import com.mymicroservice.user.dtos.login.LoginReqDto;
import com.mymicroservice.user.dtos.register.RegisterReqDto;
import com.mymicroservice.user.dtos.register.RegisterResDto;
import com.mymicroservice.user.dtos.token.RefreshReqDto;
import com.mymicroservice.user.dtos.token.TokenResDto;
import com.mymicroservice.user.entities.RefreshToken;
import com.mymicroservice.user.entities.User;
import com.mymicroservice.user.enums.Role;
import com.mymicroservice.user.enums.UserStatus;
import com.mymicroservice.user.exception.UserErrorCode;
import com.mymicroservice.user.properties.JwtProperties;
import com.mymicroservice.user.repositories.RefreshTokenRepository;
import com.mymicroservice.user.repositories.UserRepository;
import com.mymicroservice.user.service.AuthService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class AuthServiceImpl implements AuthService {
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final LoginAttemptService loginAttemptService;
    private final OutboxEventWriter outbox;
    private final TransactionTemplate transactions;

    // 帳號不存在時拿來比對的假雜湊，讓回應時間與「帳號存在但密碼錯」相近
    // 否則 bcrypt 比對約需數十毫秒，可從回應快慢推測 email 是否已註冊
    private final String dummyPasswordHash;

    public AuthServiceImpl(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
            RefreshTokenGenerator refreshTokenGenerator, PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder, JwtProperties jwtProperties, LoginAttemptService loginAttemptService,
            OutboxEventWriter outbox, TransactionTemplate transactions) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
        this.loginAttemptService = loginAttemptService;
        this.outbox = outbox;
        this.transactions = transactions;
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    public RegisterResDto register(RegisterReqDto registerReqDto) {
        // 1. 檢查 email 是否已註冊
        // 併發註冊同一個 email 時兩邊都可能通過此檢查，最後由 uq_users_email 擋下，回 DATA_CONFLICT
        if (userRepository.existsByEmail(registerReqDto.email())) {
            throw new AppException(UserErrorCode.EMAIL_ALREADY_EXISTS);
        }

        // 2. 組裝使用者：密碼雜湊後才寫入，狀態與角色固定，不由前端決定
        User user = new User();
        user.setEmail(registerReqDto.email());
        user.setPasswordHash(passwordEncoder.encode(registerReqDto.password()));
        user.setDisplayName(registerReqDto.displayName());
        user.setStatus(UserStatus.ACTIVE);
        user.getRoles().add(Role.CUSTOMER);

        return transactions.execute(status -> {
            User savedUser = userRepository.save(user);
            outbox.append("user.events", "UserRegistered", savedUser.getId().toString(),
                    Map.of("userId", savedUser.getId().toString(),
                            "email", savedUser.getEmail(), "displayName", savedUser.getDisplayName()));
            return RegisterResDto.from(savedUser);
        });
    }

    // 會寫入 refresh token，不能是 readOnly
    @Override
    public TokenResDto login(LoginReqDto loginReqDto, String clientIp) {
        String email = loginReqDto.email();

        // 1. 失敗次數已達上限就直接擋下，不比對密碼：鎖住期間就算猜中密碼也拿不到 token
        // 帳號不存在的 email 一樣會被計數、鎖住，回應和存在的帳號相同
        if (loginAttemptService.isBlocked(email, clientIp)) {
            throw new AppException(UserErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
        }

        // 2. 查使用者並比對密碼；帳號不存在與密碼錯誤一律回 INVALID_CREDENTIALS，並累計失敗次數
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            passwordEncoder.matches(loginReqDto.password(), dummyPasswordHash);
            loginAttemptService.recordFailure(email, clientIp);
            throw new AppException(UserErrorCode.INVALID_CREDENTIALS);
        }
        if (!passwordEncoder.matches(loginReqDto.password(), user.getPasswordHash())) {
            loginAttemptService.recordFailure(email, clientIp);
            throw new AppException(UserErrorCode.INVALID_CREDENTIALS);
        }

        // 3. 密碼正確：清除失敗次數（停用的帳號也是密碼正確，不算猜錯）
        loginAttemptService.reset(email, clientIp);

        // 4. 密碼正確後才檢查狀態，否則從 403 就能得知帳號存在
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(UserErrorCode.USER_DISABLED);
        }

        // 5. 簽發 access token 與 refresh token
        return transactions.execute(status -> issueTokens(user, Instant.now()));
    }

    // 輪替：舊的 refresh token 作廢，換發新的 access token 與 refresh token
    // noRollbackFor：偵測到重複使用時會先撤銷該使用者所有 token 再丟例外，撤銷必須保留下來
    // （丟出 AppException 時，這次新建立的 refresh token 也已一併被撤銷，不會留下可用的 token）
    @Override
    @Transactional(noRollbackFor = AppException.class)
    public TokenResDto refresh(RefreshReqDto refreshReqDto) {
        Instant now = Instant.now();

        // 1. 用雜湊值查 token；查不到可能是亂填、或使用者已被刪除（FK cascade）
        RefreshToken refreshToken = refreshTokenRepository
                .findByTokenHash(refreshTokenGenerator.hash(refreshReqDto.refreshToken()))
                .orElseThrow(() -> new AppException(UserErrorCode.REFRESH_TOKEN_INVALID));

        // 2. 已撤銷的 token 又被拿來用：剛輪替掉的在寬限期內放行（多分頁同時 refresh、回應遺失後重送）；
        // 其他情況代表 token 可能被竊取，撤銷該使用者所有 token，使用者需重新登入
        if (refreshToken.isRevoked()) {
            requireReuseWithinGrace(refreshToken, now);
        }

        // 3. 過期只是自然失效，不需要撤銷其他 token
        if (refreshToken.isExpired(now)) {
            throw new AppException(UserErrorCode.REFRESH_TOKEN_INVALID);
        }

        // 4. 帳號停用後不再換發；順便撤銷所有 token，避免之後恢復時舊 token 又能用
        User user = userRepository.findWithRolesById(refreshToken.getUserId())
                .orElseThrow(() -> new AppException(UserErrorCode.REFRESH_TOKEN_INVALID));
        if (user.getStatus() != UserStatus.ACTIVE) {
            refreshTokenRepository.revokeAllActiveByUserId(user.getId(), now);
            throw new AppException(UserErrorCode.USER_DISABLED);
        }

        // 5. 先建立新的 refresh token：輪替舊 token 時要記錄換成哪一個
        IssuedRefreshToken newRefreshToken = issueRefreshToken(user, now);

        // 6. 條件式輪替舊 token（第 2 步已放行的寬限期 token 本來就已撤銷，不用再輪替）
        // 更新 0 筆代表同一個 token 被另一個請求搶先輪替，例如兩個分頁同時 refresh：
        // 重新讀取後一樣交給寬限期判斷；不放行時撤銷全部，剛建立的新 token 也會一起被撤銷
        if (!refreshToken.isRevoked()
                && refreshTokenRepository.rotateIfActive(refreshToken.getId(), newRefreshToken.id(), now) == 0) {
            RefreshToken latest = refreshTokenRepository.findById(refreshToken.getId()).orElse(null);
            if (latest == null) {
                revokeAllOnReuse(user.getId(), now);
            } else {
                requireReuseWithinGrace(latest, now);
            }
        }

        // 7. 換發；roles 從 DB 重新讀取，角色有異動時新的 access token 會反映
        return toTokenResDto(issueAccessToken(user, now), newRefreshToken);
    }

    // 撤銷這一個 refresh token；同一位使用者其他裝置的 token 不受影響
    // 不存在、已撤銷、已過期都直接結束，不回錯誤：結果都是「這個 token 不能再用」，也不讓呼叫者探測 token 狀態
    // access token 無法撤銷，會在到期（最多 15 分鐘）前繼續有效，前端登出時要自行丟掉
    @Override
    @Transactional
    public void logout(RefreshReqDto refreshReqDto) {
        refreshTokenRepository.revokeByTokenHashIfActive(
                refreshTokenGenerator.hash(refreshReqDto.refreshToken()), Instant.now());
    }

    // 寬限條件全部成立才放行，否則視為重複使用：
    //   - 因輪替而撤銷（replaced_by 有值）：登出、停用、重複使用偵測撤銷的都沒有，不給寬限
    //   - 撤銷後還在寬限時間內
    //   - 換成的新 token 仍有效：新 token 被撤銷代表整組已被撤銷（或已登出），不能讓舊 token 把登入狀態救回來
    private void requireReuseWithinGrace(RefreshToken token, Instant now) {
        Duration grace = jwtProperties.refreshTokenReuseGrace();
        boolean withinGrace = grace.isPositive()
                && token.getReplacedBy() != null
                && now.isBefore(token.getRevokedAt().plus(grace))
                && refreshTokenRepository.existsByIdAndRevokedAtIsNull(token.getReplacedBy());
        if (!withinGrace) {
            revokeAllOnReuse(token.getUserId(), now);
        }
        log.info("rotated refresh token {} reused within grace period, issuing new tokens", token.getId());
    }

    // 一律丟出例外；回傳型別寫成 void，呼叫端之後的程式不會執行
    private void revokeAllOnReuse(UUID userId, Instant now) {
        int revoked = refreshTokenRepository.revokeAllActiveByUserId(userId, now);
        log.warn("refresh token reuse detected for user {}, revoked {} active token(s)", userId, revoked);
        throw new AppException(UserErrorCode.REFRESH_TOKEN_INVALID);
    }

    private TokenResDto issueTokens(User user, Instant now) {
        return toTokenResDto(issueAccessToken(user, now), issueRefreshToken(user, now));
    }

    private TokenResDto toTokenResDto(String accessToken, IssuedRefreshToken refreshToken) {
        return TokenResDto.bearer(accessToken, jwtProperties.accessTokenTtl().toSeconds(),
                refreshToken.token(), jwtProperties.refreshTokenTtl().toSeconds());
    }

    private String issueAccessToken(User user, Instant now) {
        // roles 放字串陣列，例如 ["CUSTOMER"]，Gateway 轉成 X-User-Roles
        List<String> roles = user.getRoles().stream().map(Role::name).sorted().toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                // sub 放 userId 而非 email：email 之後可能會改
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(jwtProperties.accessTokenTtl()))
                .claim("roles", roles)
                .build();
        // kid 不用自己設，NimbusJwtEncoder 會從選到的 JWK 帶入
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();

        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    // 回傳原始 token（只會出現在這次回應中），DB 只存雜湊值；id 給輪替時寫入 replaced_by
    private IssuedRefreshToken issueRefreshToken(User user, Instant now) {
        String token = refreshTokenGenerator.generate();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(user.getId());
        refreshToken.setTokenHash(refreshTokenGenerator.hash(token));
        refreshToken.setExpiresAt(now.plus(jwtProperties.refreshTokenTtl()));
        RefreshToken saved = refreshTokenRepository.save(refreshToken);

        return new IssuedRefreshToken(saved.getId(), token);
    }

    private record IssuedRefreshToken(UUID id, String token) {
    }
}
