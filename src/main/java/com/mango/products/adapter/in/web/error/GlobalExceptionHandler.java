package com.mango.products.adapter.in.web.error;

import com.mango.products.application.exception.PriceNotFoundException;
import com.mango.products.application.exception.PriceOverlapException;
import com.mango.products.application.exception.ProductNotFoundException;
import com.mango.products.application.exception.ExchangeRateUnavailableException;
import com.mango.products.domain.exception.DomainValidationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.UnsatisfiedServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<ApiViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ApiViolation(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(ApiViolation::field))
                .toList();
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", request, violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        List<ApiViolation> violations = exception.getConstraintViolations().stream()
                .map(violation -> new ApiViolation(lastPathNode(violation), violation.getMessage()))
                .sorted(Comparator.comparing(ApiViolation::field))
                .toList();
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed", request, violations);
    }

    @ExceptionHandler(DomainValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleDomainValidation(
            DomainValidationException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                exception.getMessage(),
                request,
                List.of()
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Request validation failed",
                request,
                List.of(new ApiViolation(exception.getName(), "must be a valid value"))
        );
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Request validation failed",
                request,
                List.of(new ApiViolation(exception.getParameterName(), "is required"))
        );
    }

    @ExceptionHandler(UnsatisfiedServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleUnsatisfiedParameters(
            UnsatisfiedServletRequestParameterException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Request validation failed",
                request,
                List.of()
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableMessage(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request body", request, List.of());
    }

    @ExceptionHandler(ProductNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleProductNotFound(
            ProductNotFoundException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "Product was not found", request, List.of());
    }

    @ExceptionHandler(PriceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handlePriceNotFound(
            PriceNotFoundException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.NOT_FOUND, "PRICE_NOT_FOUND", "Price was not found", request, List.of());
    }

    @ExceptionHandler(PriceOverlapException.class)
    public ResponseEntity<ApiErrorResponse> handlePriceOverlap(
            PriceOverlapException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                "PRICE_OVERLAP",
                "The price period overlaps an existing price",
                request,
                List.of()
        );
    }

    @ExceptionHandler(ExchangeRateUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleExchangeRateUnavailable(
            ExchangeRateUnavailableException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_UNAVAILABLE",
                "Currency conversion is temporarily unavailable",
                request,
                List.of()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unexpected error while handling {}", request.getRequestURI(), exception);
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "An unexpected error occurred",
                request,
                List.of()
        );
    }

    private static ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            List<ApiViolation> violations
    ) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                Instant.now(),
                status.value(),
                code,
                message,
                request.getRequestURI(),
                violations
        ));
    }

    private static String lastPathNode(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int separator = path.lastIndexOf('.');
        return separator < 0 ? path : path.substring(separator + 1);
    }
}
