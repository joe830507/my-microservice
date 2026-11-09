package com.mymicroservice.user.service;

import com.mymicroservice.user.dtos.login.LoginReqDto;
import com.mymicroservice.user.dtos.register.RegisterReqDto;
import com.mymicroservice.user.dtos.register.RegisterResDto;
import com.mymicroservice.user.dtos.token.RefreshReqDto;
import com.mymicroservice.user.dtos.token.TokenResDto;

public interface AuthService {

    public RegisterResDto register(RegisterReqDto registerReqDto);

    public TokenResDto login(LoginReqDto loginReqDto);

    public TokenResDto refresh(RefreshReqDto refreshReqDto);

    public void logout(RefreshReqDto refreshReqDto);
}
