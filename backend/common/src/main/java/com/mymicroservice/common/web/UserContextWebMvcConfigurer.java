package com.mymicroservice.common.web;

import java.util.List;

import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 註冊 @CurrentUserId 與 @RequireRoles 的處理器，由 CommonAutoConfiguration 建立
public class UserContextWebMvcConfigurer implements WebMvcConfigurer {

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserIdArgumentResolver());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RequireRolesInterceptor());
    }
}
