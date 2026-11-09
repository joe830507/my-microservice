package com.mymicroservice.user.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.mymicroservice.user.entities.User;
import com.mymicroservice.user.enums.Role;
import com.mymicroservice.user.enums.UserStatus;
import com.mymicroservice.user.properties.BootstrapAdminProperties;
import com.mymicroservice.user.repositories.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// dev 環境啟動時建立第一個 ADMIN，讓後台 API 在本機有帳號可以用
// 不用 Flyway migration：密碼雜湊會寫死在 SQL，每個環境也都會有同一個帳號
// 正式環境沒有 dev profile，不會執行
//
// 冪等：email 已存在就跳過，不更新密碼、不補角色，重啟多次結果都一樣
// 不在 HTTP 請求中，稽核欄位由 HeaderAuditorAware 記為 SYSTEM_USER_ID
@Slf4j
@Component
@Profile("dev")
@RequiredArgsConstructor
public class BootstrapAdminInitializer implements ApplicationRunner {
    private final BootstrapAdminProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmail(properties.email())) {
            log.debug("bootstrap admin {} already exists, skipped", properties.email());
            return;
        }

        // 只給 ADMIN；需要以一般會員身分購物時另外註冊
        User admin = new User();
        admin.setEmail(properties.email());
        admin.setPasswordHash(passwordEncoder.encode(properties.password()));
        admin.setDisplayName(properties.displayName());
        admin.setStatus(UserStatus.ACTIVE);
        admin.getRoles().add(Role.ADMIN);

        try {
            // saveAndFlush：確保唯一約束的例外在這裡丟出，才能被下面的 catch 接住
            userRepository.saveAndFlush(admin);
            log.info("bootstrap admin {} created", properties.email());
        } catch (DataIntegrityViolationException exception) {
            // 多個實例同時啟動時，其他實例已先建立同一個 email
            log.info("bootstrap admin {} was created concurrently, skipped", properties.email());
        }
    }
}
