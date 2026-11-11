package com.mymicroservice.user.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.mymicroservice.user.entities.User;
import com.mymicroservice.user.enums.Role;
import com.mymicroservice.user.enums.UserStatus;
import com.mymicroservice.user.properties.BootstrapAdminProperties;
import com.mymicroservice.user.repositories.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 啟動時建立第一個 ADMIN（SEC-12）：註冊只能建立 CUSTOMER，沒有這個就沒有任何方式取得管理員。
// 由設定開關 app.bootstrap-admin.enabled（環境變數 BOOTSTRAP_ADMIN_ENABLED）控制，預設關閉；
// 本機的 dev profile 會自動開啟（application-dev.yml）。不再綁在 dev profile 上：K8s 等非 dev 環境也需要建立第一個管理員，
// 不應該為了這件事把整個服務切成 dev profile（以後 dev profile 加了別的開發用設定就會一起帶進去）
// 第一個管理員建好之後，關掉開關並把 Secret 裡的密碼換掉：已存在的帳號不會被這個類別更新
// 不用 Flyway migration：密碼雜湊會寫死在 SQL，每個環境也都會有同一個帳號
//
// 冪等：email 已存在就跳過，不更新密碼、不補角色，重啟多次結果都一樣
// 不在 HTTP 請求中，稽核欄位由 HeaderAuditorAware 記為 SYSTEM_USER_ID
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.bootstrap-admin", name = "enabled", havingValue = "true")
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
