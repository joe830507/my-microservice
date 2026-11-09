package com.mymicroservice.user.service;

import com.mymicroservice.user.dtos.register.RegisterReqDto;
import com.mymicroservice.user.dtos.register.RegisterResDto;

public interface AuthService {

    public RegisterResDto register(RegisterReqDto registerReqDto);
}
