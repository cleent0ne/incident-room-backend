package com.incidentroom.repository;

import com.incidentroom.domain.AppUser;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface AppUserRepository extends MongoRepository<AppUser, String> {

    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<AppUser> findByOauthProviderAndOauthProviderId(String oauthProvider, String oauthProviderId);
}
