package com.mymicroservice.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 標在 controller 方法或類別上，限定呼叫者必須擁有其中任一角色（依 X-User-Roles 判斷）
// 由 RequireRolesInterceptor 在參數綁定與驗證之前檢查：
//   沒有合法的 X-User-Id → 401 UNAUTHENTICATED；角色不符 → 403 FORBIDDEN
// 方法與類別都有標時，以方法上的為準
//
//   @RequireRoles("ADMIN")
//   public GetUsersResDto getUsers(...)
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireRoles {

    // 角色名稱，區分大小寫，與 JWT 裡的 roles 一致
    String[] value();
}
