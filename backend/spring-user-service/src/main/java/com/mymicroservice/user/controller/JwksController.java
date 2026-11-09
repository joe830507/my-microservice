package com.mymicroservice.user.controller;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.jwk.JWKSet;

// 公開 JWT 驗簽用的公鑰（JWK Set，RFC 7517），Gateway 以此驗證 user-service 簽發的 token；不需要 JWT
// 內容來自 JwtConfig#publicJwkSet：目前的簽章公鑰 + 金鑰輪替前的舊公鑰，Gateway 依 token 的 kid 挑選
@RestController
@RequestMapping("/api/v1/auth")
public class JwksController {

    // 金鑰在啟動後不會變，先轉好 JSON，不必每次請求重新組
    private final Map<String, Object> jwkSetJson;

    public JwksController(JWKSet publicJwkSet) {
        // publicJwkSet 本身只有公鑰；toJSONObject() 預設也只輸出公開部分（publicKeysOnly = true），雙重保險
        this.jwkSetJson = publicJwkSet.toJSONObject();
    }

    // 允許快取：Gateway 本身也會快取 JWKS，遇到未知的 kid 才重新抓
    // 之後輪替金鑰時，新舊公鑰會同時發布一段時間，快取 5 分鐘不會造成驗簽失敗
    @GetMapping(value = "/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(jwkSetJson);
    }
}
