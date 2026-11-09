package com.mymicroservice.user.service;

import com.mymicroservice.user.dtos.login.LoginReqDto;
import com.mymicroservice.user.dtos.login.LoginResDto;
import com.mymicroservice.user.dtos.register.RegisterReqDto;
import com.mymicroservice.user.dtos.register.RegisterResDto;

public interface AuthService {

    public RegisterResDto register(RegisterReqDto registerReqDto);

    public LoginResDto login(LoginReqDto loginReqDto);
}
