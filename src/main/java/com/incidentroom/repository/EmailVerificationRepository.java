package com.incidentroom.repository;

import com.incidentroom.domain.EmailVerification;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface EmailVerificationRepository extends MongoRepository<EmailVerification, String> {

    Optional<EmailVerification> findTopByUserIdOrderByCreatedAtDesc(String userId);

    List<EmailVerification> findByUserId(String userId);

    void deleteByUserId(String userId);
}
