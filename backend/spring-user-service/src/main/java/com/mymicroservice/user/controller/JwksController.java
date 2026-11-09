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
import com.nimbusds.jose.jwk.RSAKey;

// 公開 JWT 驗簽用的公鑰（JWK Set，RFC 7517），Gateway 以此驗證 user-service 簽發的 token；不需要 JWT
// 與簽章共用同一個 RSAKey bean，kid 一定一致
@RestController
@RequestMapping("/api/v1/auth")
public class JwksController {

    // 金鑰在啟動後不會變，先轉好 JSON，不必每次請求重新組
    private final Map<String, Object> jwkSetJson;

    public JwksController(RSAKey rsaKey) {
        // toJSONObject() 預設只輸出公開部分（publicKeysOnly = true），私鑰的 d、p、q 等不會出現
        this.jwkSetJson = new JWKSet(rsaKey).toJSONObject();
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
