package com.incidentroom.service;

import com.incidentroom.common.exception.TooManyRequestsException;
import com.incidentroom.domain.EmailVerification;
import com.incidentroom.repository.EmailVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VerificationCodeServiceTest {

    @Mock
    private EmailVerificationRepository repository;

    private VerificationCodeService verificationCodeService;

    @BeforeEach
    void setUp() {
        verificationCodeService = new VerificationCodeService(repository, 10, 5, 1);
    }

    @Test
    void issueNewCode_withinCooldown_throwsTooManyRequestsException() {
        String userId = "user-123";
        EmailVerification recent = EmailVerification.builder()
                .userId(userId)
                .codeHash("hash")
                .createdAt(Instant.now().minusSeconds(30)) // 30s ago, within 1 min cooldown
                .expiresAt(Instant.now().plusSeconds(600))
                .build();

        when(repository.findTopByUserIdOrderByCreatedAtDesc(userId)).thenReturn(Optional.of(recent));

        assertThatThrownBy(() -> verificationCodeService.issueNewCode(userId))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessageContaining("Please wait before requesting another verification code.");

        verify(repository, never()).deleteByUserId(any());
    }

    @Test
    void issueNewCode_afterCooldown_succeeds() {
        String userId = "user-123";
        EmailVerification old = EmailVerification.builder()
                .userId(userId)
                .codeHash("hash")
                .createdAt(Instant.now().minusSeconds(90)) // 90s ago, outside 1 min cooldown
                .expiresAt(Instant.now().plusSeconds(600))
                .build();

        when(repository.findTopByUserIdOrderByCreatedAtDesc(userId)).thenReturn(Optional.of(old));

        String code = verificationCodeService.issueNewCode(userId);
        assertThat(code).hasSize(6);

        verify(repository).deleteByUserId(userId);
        verify(repository).save(any(EmailVerification.class));
    }
}
