package com.example.userservice.user;

import com.example.userservice.user.dto.LastLoginResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserQueryService {

    private final UserRepository userRepository;

    public UserQueryService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public LastLoginResponse getLastLogin(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: id=" + userId));
        return new LastLoginResponse(user.getEmail(), user.getLastLoginAt());
    }
}
