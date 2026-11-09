package com.mymicroservice.user.service.impl;

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

import com.mymicroservice.common.exception.AppException;
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
@Transactional
public class AuthServiceImpl implements AuthService {
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenGenerator refreshTokenGenerator;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    // 帳號不存在時拿來比對的假雜湊，讓回應時間與「帳號存在但密碼錯」相近
    // 否則 bcrypt 比對約需數十毫秒，可從回應快慢推測 email 是否已註冊
    private final String dummyPasswordHash;

    public AuthServiceImpl(UserRepository userRepository, RefreshTokenRepository refreshTokenRepository,
            RefreshTokenGenerator refreshTokenGenerator, PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder, JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.refreshTokenGenerator = refreshTokenGenerator;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.jwtProperties = jwtProperties;
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

        User savedUser = userRepository.save(user);
        return RegisterResDto.from(savedUser);
    }

    // 會寫入 refresh token，不能是 readOnly
    @Override
    public TokenResDto login(LoginReqDto loginReqDto) {
        // 1. 查使用者並比對密碼；帳號不存在與密碼錯誤一律回 INVALID_CREDENTIALS
        User user = userRepository.findByEmail(loginReqDto.email()).orElse(null);
        if (user == null) {
            passwordEncoder.matches(loginReqDto.password(), dummyPasswordHash);
            throw new AppException(UserErrorCode.INVALID_CREDENTIALS);
        }
        if (!passwordEncoder.matches(loginReqDto.password(), user.getPasswordHash())) {
            throw new AppException(UserErrorCode.INVALID_CREDENTIALS);
        }

        // 2. 密碼正確後才檢查狀態，否則從 403 就能得知帳號存在
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(UserErrorCode.USER_DISABLED);
        }

        // 3. 簽發 access token 與 refresh token
        return issueTokens(user, Instant.now());
    }

    // 輪替：舊的 refresh token 作廢，換發新的 access token 與 refresh token
    // noRollbackFor：偵測到重複使用時會先撤銷該使用者所有 token 再丟例外，撤銷必須保留下來
    // （這個方法丟出 AppException 之前的寫入只有撤銷，不會留下做一半的換發）
    @Override
    @Transactional(noRollbackFor = AppException.class)
    public TokenResDto refresh(RefreshReqDto refreshReqDto) {
        Instant now = Instant.now();

        // 1. 用雜湊值查 token；查不到可能是亂填、或使用者已被刪除（FK cascade）
        RefreshToken refreshToken = refreshTokenRepository
                .findByTokenHash(refreshTokenGenerator.hash(refreshReqDto.refreshToken()))
                .orElseThrow(() -> new AppException(UserErrorCode.REFRESH_TOKEN_INVALID));

        // 2. 已撤銷的 token 又被拿來用：正常的前端不會這樣做，代表 token 可能被竊取
        // 撤銷該使用者所有 token，讓攻擊者與使用者手上的 refresh token 都失效，使用者需重新登入
        if (refreshToken.isRevoked()) {
            revokeAllOnReuse(refreshToken.getUserId(), now);
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

        // 5. 條件式撤銷舊 token；回傳 0 代表同一個 token 被另一個請求搶先用掉，視為重複使用
        if (refreshTokenRepository.revokeIfActive(refreshToken.getId(), now) == 0) {
            revokeAllOnReuse(user.getId(), now);
        }

        // 6. 換發；roles 從 DB 重新讀取，角色有異動時新的 access token 會反映
        return issueTokens(user, now);
    }

    private void revokeAllOnReuse(UUID userId, Instant now) {
        int revoked = refreshTokenRepository.revokeAllActiveByUserId(userId, now);
        log.warn("refresh token reuse detected for user {}, revoked {} active token(s)", userId, revoked);
        throw new AppException(UserErrorCode.REFRESH_TOKEN_INVALID);
    }

    private TokenResDto issueTokens(User user, Instant now) {
        String accessToken = issueAccessToken(user, now);
        String refreshToken = issueRefreshToken(user, now);
        return TokenResDto.bearer(accessToken, jwtProperties.accessTokenTtl().toSeconds(),
                refreshToken, jwtProperties.refreshTokenTtl().toSeconds());
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

    // 回傳原始 token（只會出現在這次回應中），DB 只存雜湊值
    private String issueRefreshToken(User user, Instant now) {
        String token = refreshTokenGenerator.generate();

        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(user.getId());
        refreshToken.setTokenHash(refreshTokenGenerator.hash(token));
        refreshToken.setExpiresAt(now.plus(jwtProperties.refreshTokenTtl()));
        refreshTokenRepository.save(refreshToken);

        return token;
    }
}
