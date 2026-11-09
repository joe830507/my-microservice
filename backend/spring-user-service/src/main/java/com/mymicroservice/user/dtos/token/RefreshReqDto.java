package com.mymicroservice.user.dtos.token;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// refresh 與 logout 共用：body 都只有 refresh token
// 簽發的 refresh token 是 32 bytes 的 base64url（43 字元）；上限只是擋掉異常大的請求
public record RefreshReqDto(
        @NotBlank @Size(max = 100) String refreshToken) {

}
