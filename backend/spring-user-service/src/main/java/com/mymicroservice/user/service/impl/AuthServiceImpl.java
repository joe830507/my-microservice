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
import com.mymicroservice.user.dtos.login.LoginResDto;
import com.mymicroservice.user.dtos.register.RegisterReqDto;
import com.mymicroservice.user.dtos.register.RegisterResDto;
import com.mymicroservice.user.entities.User;
import com.mymicroservice.user.enums.Role;
import com.mymicroservice.user.enums.UserStatus;
import com.mymicroservice.user.exception.UserErrorCode;
import com.mymicroservice.user.properties.JwtProperties;
import com.mymicroservice.user.repositories.UserRepository;
import com.mymicroservice.user.service.AuthService;

@Service
@Transactional
public class AuthServiceImpl implements AuthService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;

    // 帳號不存在時拿來比對的假雜湊，讓回應時間與「帳號存在但密碼錯」相近
    // 否則 bcrypt 比對約需數十毫秒，可從回應快慢推測 email 是否已註冊
    private final String dummyPasswordHash;

    public AuthServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder, JwtProperties jwtProperties) {
        this.userRepository = userRepository;
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

    @Override
    @Transactional(readOnly = true)
    public LoginResDto login(LoginReqDto loginReqDto) {
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

        // 3. 簽發 access token
        return LoginResDto.bearer(issueAccessToken(user), jwtProperties.accessTokenTtl().toSeconds());
    }

    private String issueAccessToken(User user) {
        Instant now = Instant.now();
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
}
