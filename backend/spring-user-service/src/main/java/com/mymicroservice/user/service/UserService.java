package com.mymicroservice.user.service;

import java.util.UUID;

import com.mymicroservice.user.dtos.me.GetMeResDto;

public interface UserService {

    public GetMeResDto getMe(UUID userId);
}
