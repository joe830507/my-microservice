package com.mymicroservice.common.web;

// Gateway 驗證 JWT 後帶給下游服務的身分 header；下游服務信任這些值，不自行驗 token
// Gateway 不依賴 common，需自行定義相同名稱
public final class UserContextHeaders {

    public static final String USER_ID = "X-User-Id";

    // 以逗號分隔，例如 CUSTOMER,ADMIN
    public static final String USER_ROLES = "X-User-Roles";

    private UserContextHeaders() {
    }
}
