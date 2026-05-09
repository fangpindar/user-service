package com.example.userservice.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface ActivationTokenRepository extends JpaRepository<ActivationToken, Long> {

    Optional<ActivationToken> findByToken(String token);

    @Modifying
    @Query("update ActivationToken a set a.usedAt = :now where a.userId = :userId and a.usedAt is null")
    int markAllUnusedAsUsedForUser(@Param("userId") Long userId, @Param("now") Instant now);
}
