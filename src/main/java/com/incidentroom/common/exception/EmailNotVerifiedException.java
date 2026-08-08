package com.incidentroom.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Deliberately distinct from a generic auth failure — the frontend needs to route
 * the user to the verification screen, not a dead-end "login failed" message.
 */
public class EmailNotVerifiedException extends ApiException {
    public EmailNotVerifiedException(String message) {
        super(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", message);
    }
}
