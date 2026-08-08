package com.incidentroom.common.exception;

import com.incidentroom.common.logging.CorrelationIdFilter;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

import static com.incidentroom.common.logging.CorrelationIdFilter.MDC_KEY;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handleApiException(ApiException ex, HttpServletRequest request) {
        // 4xx application errors are expected traffic, not incidents — log at INFO/WARN, not ERROR,
        // so they don't trip error-rate alerting the way a genuine 500 should.
        log.warn("Handled API exception: code={} status={} path={}", ex.getCode(), ex.getStatus(), request.getRequestURI());
        return ResponseEntity.status(ex.getStatus())
                .body(ApiErrorResponse.of(ex.getCode(), ex.getMessage(), correlationId()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        List<ApiErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiErrorResponse.FieldError(fe.getField(), messageFor(fe)))
                .toList();
        return ResponseEntity.badRequest().body(ApiErrorResponse.ofFieldErrors(correlationId(), fieldErrors));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Genuinely unexpected — this is what should page/alert. Full exception logged server-side only;
        // the client gets a generic message with the correlation ID so support can look it up without
        // any internal detail ever reaching the response body.
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of("INTERNAL_ERROR",
                        "Something went wrong on our end. If this persists, contact support with the reference below.",
                        correlationId()));
    }

    private static String messageFor(FieldError fe) {
        return fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value.";
    }

    private static String correlationId() {
        return MDC.get(MDC_KEY);
    }
}
