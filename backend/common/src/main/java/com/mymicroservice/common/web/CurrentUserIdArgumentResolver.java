package com.mymicroservice.common.web;

import java.util.UUID;

import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.mymicroservice.common.exception.AppException;
import com.mymicroservice.common.exception.CommonErrorCode;

// 處理 @CurrentUserId UUID 參數
// 不用 @RequestHeader UUID：缺 header 會變成 400、格式錯誤會落到 500，這裡兩種情況都代表沒有合法身分，統一回 401
public class CurrentUserIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class)
                && UUID.class.equals(parameter.getParameterType());
    }

    @Override
    public UUID resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
            NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        return UserContext.parseUserId(webRequest.getHeader(UserContextHeaders.USER_ID))
                .orElseThrow(() -> new AppException(CommonErrorCode.UNAUTHENTICATED));
    }
}
