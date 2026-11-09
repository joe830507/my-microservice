package com.mymicroservice.common.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.mymicroservice.common.web.UserContextHeaders;

class HeaderAuditorAwareTest {

    private final HeaderAuditorAware auditorAware = new HeaderAuditorAware();

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void returnsSystemUserWhenNotInRequest() {
        assertThat(auditorAware.getCurrentAuditor()).contains(HeaderAuditorAware.SYSTEM_USER_ID);
    }

    @Test
    void returnsHeaderUserIdWhenValid() {
        UUID userId = UUID.randomUUID();
        bindRequestWithUserId(userId.toString());

        assertThat(auditorAware.getCurrentAuditor()).contains(userId);
    }

    @Test
    void returnsSystemUserWhenHeaderMissing() {
        bindRequestWithUserId(null);

        assertThat(auditorAware.getCurrentAuditor()).contains(HeaderAuditorAware.SYSTEM_USER_ID);
    }

    @Test
    void returnsSystemUserWhenHeaderInvalid() {
        bindRequestWithUserId("not-a-uuid");

        assertThat(auditorAware.getCurrentAuditor()).contains(HeaderAuditorAware.SYSTEM_USER_ID);
    }

    private void bindRequestWithUserId(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (userId != null) {
            request.addHeader(UserContextHeaders.USER_ID, userId);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
