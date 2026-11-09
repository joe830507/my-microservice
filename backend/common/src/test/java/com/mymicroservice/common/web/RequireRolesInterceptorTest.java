package com.mymicroservice.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.common.exception.CommonErrorCode;

class RequireRolesInterceptorTest {
    private static final String USER_ID = UUID.randomUUID().toString();

    private final RequireRolesInterceptor interceptor = new RequireRolesInterceptor();

    // ---------- 沒有 @RequireRoles ----------

    @Test
    void noAnnotation_passesWithoutHeaders() throws Exception {
        assertThat(preHandle(handler(PublicController.class, "open"), request(null, null))).isTrue();
    }

    @Test
    void nonHandlerMethod_passes() {
        // 例如靜態資源，handler 不是 HandlerMethod
        assertThat(interceptor.preHandle(request(null, null), new MockHttpServletResponse(), new Object())).isTrue();
    }

    // ---------- 方法上的 @RequireRoles ----------

    @Test
    void methodAnnotation_matchingRole_passes() throws Exception {
        assertThat(preHandle(handler(PublicController.class, "adminOnly"), request(USER_ID, "CUSTOMER,ADMIN")))
                .isTrue();
    }

    @Test
    void methodAnnotation_anyOfRoles_passes() throws Exception {
        assertThat(preHandle(handler(PublicController.class, "adminOrStaff"), request(USER_ID, "STAFF"))).isTrue();
    }

    @Test
    void missingUserId_throws401EvenWithRole() {
        assertErrorCode(handler(PublicController.class, "adminOnly"), request(null, "ADMIN"),
                CommonErrorCode.UNAUTHENTICATED);
    }

    @Test
    void malformedUserId_throws401() {
        assertErrorCode(handler(PublicController.class, "adminOnly"), request("not-a-uuid", "ADMIN"),
                CommonErrorCode.UNAUTHENTICATED);
    }

    @Test
    void missingRolesHeader_throws403() {
        assertErrorCode(handler(PublicController.class, "adminOnly"), request(USER_ID, null),
                CommonErrorCode.FORBIDDEN);
    }

    @Test
    void wrongRole_throws403() {
        assertErrorCode(handler(PublicController.class, "adminOnly"), request(USER_ID, "CUSTOMER"),
                CommonErrorCode.FORBIDDEN);
    }

    @Test
    void roleComparison_isCaseSensitive() {
        assertErrorCode(handler(PublicController.class, "adminOnly"), request(USER_ID, "admin"),
                CommonErrorCode.FORBIDDEN);
    }

    // ---------- 類別上的 @RequireRoles ----------

    @Test
    void classAnnotation_appliesToMethodsWithoutOwnAnnotation() {
        assertErrorCode(handler(AdminController.class, "list"), request(USER_ID, "CUSTOMER"),
                CommonErrorCode.FORBIDDEN);
    }

    @Test
    void methodAnnotation_overridesClassAnnotation() throws Exception {
        assertThat(preHandle(handler(AdminController.class, "customerAllowed"), request(USER_ID, "CUSTOMER")))
                .isTrue();
    }

    // ---------- helpers ----------

    private boolean preHandle(HandlerMethod handler, MockHttpServletRequest request) {
        return interceptor.preHandle(request, new MockHttpServletResponse(), handler);
    }

    private void assertErrorCode(HandlerMethod handler, MockHttpServletRequest request, CommonErrorCode expected) {
        assertThatThrownBy(() -> preHandle(handler, request))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(expected));
    }

    private static MockHttpServletRequest request(String userId, String roles) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (userId != null) {
            request.addHeader(UserContextHeaders.USER_ID, userId);
        }
        if (roles != null) {
            request.addHeader(UserContextHeaders.USER_ROLES, roles);
        }
        return request;
    }

    private static HandlerMethod handler(Class<?> controllerType, String methodName) {
        try {
            Object controller = controllerType.getDeclaredConstructor().newInstance();
            return new HandlerMethod(controller, controllerType.getDeclaredMethod(methodName));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static class PublicController {
        void open() {
        }

        @RequireRoles("ADMIN")
        void adminOnly() {
        }

        @RequireRoles({ "ADMIN", "STAFF" })
        void adminOrStaff() {
        }
    }

    @RequireRoles("ADMIN")
    static class AdminController {
        void list() {
        }

        @RequireRoles("CUSTOMER")
        void customerAllowed() {
        }
    }
}
