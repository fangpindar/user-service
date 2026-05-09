package com.example.userservice.user.service;

import com.example.userservice.user.dto.LastLoginResponse;

public interface UserQueryService {

    LastLoginResponse getLastLogin(Long userId);
}
