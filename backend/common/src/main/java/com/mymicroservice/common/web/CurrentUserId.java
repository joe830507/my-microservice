package com.mymicroservice.common.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 標在 controller 的 UUID 參數上，注入 X-User-Id 代表的目前使用者
// 缺少或格式錯誤時回 401 UNAUTHENTICATED（由 CurrentUserIdArgumentResolver 處理）
//
//   public GetMeResDto getMe(@CurrentUserId UUID userId)
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUserId {
}
