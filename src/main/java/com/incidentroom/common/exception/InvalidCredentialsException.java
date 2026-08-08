package com.incidentroom.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Used for both "no such account" and "wrong password" — deliberately the same
 * exception/message for both, so the API never reveals which one it was (account enumeration defense).
 */
public class InvalidCredentialsException extends ApiException {
    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Incorrect email or password.");
    }

    public InvalidCredentialsException(String message) {
        super(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", message);
    }
}

