package com.incidentroom.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base for all deliberate, well-understood application errors — as opposed to unexpected
 * exceptions, which GlobalExceptionHandler treats as 500s and reports to error tracking.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
