package com.mymicroservice.user.dtos.register;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 不接受 status 與 roles：新註冊固定為 ACTIVE + CUSTOMER，避免前端自行指定成 ADMIN
// 沒有 toEntity()：密碼須經 PasswordEncoder 雜湊後才能寫入，交由 service 組裝
public record RegisterReqDto(
                // 長度對應 DB 的 VARCHAR(254)
                @NotBlank @Email @Size(max = 254) String email,

                // bcrypt 只取前 72 bytes，超過的部分會被忽略，因此上限設為 72
                // 注意 @Size 算的是字元數，含中文等多位元組字元時仍可能超過 72 bytes
                @NotBlank @Size(min = 8, max = 72) String password,

                @NotBlank @Size(max = 50) String displayName) {

}
