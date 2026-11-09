package com.mymicroservice.common.web;

import java.util.Arrays;
import java.util.Map;

import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

// 用到 @RequireRoles 的服務要在啟動類別加上 @Import(RequireRolesGuard.class)：
//   - 打包時拿到沒有這個類別的舊版 common（例如 ~/.m2 裡過期的 SNAPSHOT）→ 找不到類別，啟動失敗
//   - 有這個類別，但 RequireRolesInterceptor 沒有註冊到 Spring MVC（例如自動設定被排除、覆寫）→ 啟動失敗
// 原因：JVM 會默默忽略找不到類別的 annotation，舊版 common 沒有 RequireRoles 時，
// controller 上的 @RequireRoles 等於不存在，任何人都能呼叫後台 API，而且不會有任何錯誤或 log
// 引用必須寫在服務自己的程式碼裡，舊版 common 才會因為找不到類別而失敗；只放在 common 裡的檢查會跟著舊版一起消失
public class RequireRolesGuard implements SmartInitializingSingleton {
    private final ListableBeanFactory beanFactory;

    public RequireRolesGuard(ListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    // 所有 singleton 建立完成後檢查，interceptor 此時已經註冊到 handler mapping
    @Override
    public void afterSingletonsInstantiated() {
        Map<String, RequestMappingHandlerMapping> mappings = beanFactory
                .getBeansOfType(RequestMappingHandlerMapping.class);
        boolean registered = mappings.values().stream()
                .map(RequestMappingHandlerMapping::getAdaptedInterceptors)
                .flatMap(Arrays::stream)
                .anyMatch(RequireRolesGuard::isRequireRolesInterceptor);
        if (!registered) {
            throw new IllegalStateException("RequireRolesInterceptor is not registered: @RequireRoles would be "
                    + "ignored and admin APIs would be open to everyone. Check that CommonAutoConfiguration is "
                    + "loaded and the common module is up to date (build with -am).");
        }
    }

    private static boolean isRequireRolesInterceptor(HandlerInterceptor interceptor) {
        HandlerInterceptor actual = interceptor instanceof MappedInterceptor mapped ? mapped.getInterceptor()
                : interceptor;
        return actual instanceof RequireRolesInterceptor;
    }
}
