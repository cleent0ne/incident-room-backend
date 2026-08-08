package com.incidentroom.repository;

import com.incidentroom.domain.PasswordResetToken;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface PasswordResetTokenRepository extends MongoRepository<PasswordResetToken, String> {
    Optional<PasswordResetToken> findByUserId(String userId);
    void deleteByUserId(String userId);
}
