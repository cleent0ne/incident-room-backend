package com.incidentroom.repository;

import com.incidentroom.domain.RefreshToken;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends MongoRepository<RefreshToken, String> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByFamilyIdAndRevokedAtIsNull(String familyId);

    List<RefreshToken> findByUserIdAndRevokedAtIsNull(String userId);
}
