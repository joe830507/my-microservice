package com.mymicroservice.common.web;

import java.util.Arrays;
import java.util.Set;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.common.exception.CommonErrorCode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// 檢查 @RequireRoles；用 interceptor 而不是在 controller 裡檢查，
// 是因為 preHandle 在參數綁定與 @Valid 之前執行，沒有權限的呼叫者一律先收到 401／403，不會先拿到 400
// 這裡丟出的 AppException 一樣由 GlobalExceptionHandler 轉成 ProblemDetail
public class RequireRolesInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireRoles requireRoles = findRequireRoles(handlerMethod);
        if (requireRoles == null) {
            return true;
        }

        if (UserContext.parseUserId(request.getHeader(UserContextHeaders.USER_ID)).isEmpty()) {
            throw new AppException(CommonErrorCode.UNAUTHENTICATED);
        }
        Set<String> roles = UserContext.parseRoles(request.getHeader(UserContextHeaders.USER_ROLES));
        if (Arrays.stream(requireRoles.value()).noneMatch(roles::contains)) {
            throw new AppException(CommonErrorCode.FORBIDDEN);
        }
        return true;
    }

    // 方法上的優先，沒有才看類別
    private static RequireRoles findRequireRoles(HandlerMethod handlerMethod) {
        RequireRoles onMethod = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(),
                RequireRoles.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RequireRoles.class);
    }
}
