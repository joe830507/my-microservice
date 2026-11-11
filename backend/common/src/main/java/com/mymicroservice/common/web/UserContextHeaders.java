package com.mymicroservice.common.web;

// Gateway 驗證 JWT 後帶給下游服務的身分 header；下游服務信任這些值，不自行驗 token
// Gateway 不依賴 common，需自行定義相同名稱
public final class UserContextHeaders {

    public static final String USER_ID = "X-User-Id";

    // 以逗號分隔，例如 CUSTOMER,ADMIN
    public static final String USER_ROLES = "X-User-Roles";

    // Gateway 解析出的用戶端來源 IP（已考慮 trusted-proxies）。下游服務在 Gateway 後面，自己看到的永遠是 Gateway 的 IP，
    // 需要依來源 IP 做判斷時（例如登入失敗鎖定）用這個值。Gateway 一定會先移除用戶端送來的同名 header 再填入
    public static final String CLIENT_IP = "X-Client-Ip";

    private UserContextHeaders() {
    }
}
