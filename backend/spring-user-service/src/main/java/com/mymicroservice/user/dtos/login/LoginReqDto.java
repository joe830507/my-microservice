package com.mymicroservice.user.dtos.login;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 不檢查密碼長度下限：規則只在註冊時套用，登入時一律交給比對結果判斷
// 上限只是擋掉異常大的請求，與註冊的 72 一致
public record LoginReqDto(
        @NotBlank @Size(max = 254) String email,

        @NotBlank @Size(max = 72) String password) {

}
