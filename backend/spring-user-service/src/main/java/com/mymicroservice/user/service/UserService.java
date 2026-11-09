package com.mymicroservice.user.service;

import java.util.UUID;

import com.mymicroservice.user.dtos.me.GetMeResDto;
import com.mymicroservice.user.dtos.users.GetUsersReqDto;
import com.mymicroservice.user.dtos.users.GetUsersResDto;

public interface UserService {

    public GetMeResDto getMe(UUID userId);

    public GetUsersResDto getUsers(GetUsersReqDto getUsersReqDto);

    public void revokeAllSessions(UUID userId);
}
