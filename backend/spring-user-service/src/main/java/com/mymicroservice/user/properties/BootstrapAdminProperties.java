package com.mymicroservice.user.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 對應 application-dev.yml 的 app.bootstrap-admin，供 BootstrapAdminInitializer 建立 dev 環境的第一個 ADMIN
// 加 @Profile("dev")：@ConfigurationPropertiesScan 會掃到這個類別，不限定 profile 的話，
// 非 dev 環境沒有這些設定，@Validated 會讓服務啟動失敗
// 長度限制和 RegisterReqDto 一致，確保這個帳號之後能正常登入
@Profile("dev")
@Validated
@ConfigurationProperties(prefix = "app.bootstrap-admin")
public record BootstrapAdminProperties(
        @NotBlank @Email @Size(max = 254) String email,

        // 由環境變數 BOOTSTRAP_ADMIN_PASSWORD 提供，不寫進 yml
        @NotBlank @Size(min = 8, max = 72) String password,

        @NotBlank @Size(max = 50) @DefaultValue("Admin") String displayName) {

    // 避免 toString() 把密碼印進日誌（record 預設的 toString 會列出所有欄位）
    @Override
    public String toString() {
        return "BootstrapAdminProperties[email=" + email + ", displayName=" + displayName + "]";
    }
}
