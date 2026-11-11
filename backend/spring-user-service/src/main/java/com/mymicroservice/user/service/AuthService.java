package com.mymicroservice.user.service;

import com.mymicroservice.user.dtos.login.LoginReqDto;
import com.mymicroservice.user.dtos.register.RegisterReqDto;
import com.mymicroservice.user.dtos.register.RegisterResDto;
import com.mymicroservice.user.dtos.token.RefreshReqDto;
import com.mymicroservice.user.dtos.token.TokenResDto;

public interface AuthService {

    public RegisterResDto register(RegisterReqDto registerReqDto);

    // clientIp：Gateway 帶來的用戶端來源 IP（X-Client-Ip），用於登入失敗鎖定；沒有時傳 null
    public TokenResDto login(LoginReqDto loginReqDto, String clientIp);

    public TokenResDto refresh(RefreshReqDto refreshReqDto);

    public void logout(RefreshReqDto refreshReqDto);
}
