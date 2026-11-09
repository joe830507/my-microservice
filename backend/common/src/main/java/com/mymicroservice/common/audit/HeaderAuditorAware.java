package com.mymicroservice.common.audit;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.AuditorAware;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.mymicroservice.common.web.UserContextHeaders;

import lombok.extern.slf4j.Slf4j;

// 稽核欄位的操作人：有合法的 X-User-Id 就用它，否則記為系統使用者
//   - 未登入的請求（註冊、登入）、排程、測試等不在 HTTP 請求中的情況 → SYSTEM_USER_ID
//   - header 不是合法 UUID → SYSTEM_USER_ID，並記 warn（經過 Gateway 時不應發生）
@Slf4j
public class HeaderAuditorAware implements AuditorAware<UUID> {

    public static final UUID SYSTEM_USER_ID = new UUID(0L, 0L);

    @Override
    public Optional<UUID> getCurrentAuditor() {
        return Optional.of(resolveUserId());
    }

    private UUID resolveUserId() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return SYSTEM_USER_ID;
        }
        String header = servletAttributes.getRequest().getHeader(UserContextHeaders.USER_ID);
        if (header == null || header.isBlank()) {
            return SYSTEM_USER_ID;
        }
        try {
            return UUID.fromString(header.strip());
        } catch (IllegalArgumentException exception) {
            log.warn("invalid {} header: {}", UserContextHeaders.USER_ID, header);
            return SYSTEM_USER_ID;
        }
    }
}
