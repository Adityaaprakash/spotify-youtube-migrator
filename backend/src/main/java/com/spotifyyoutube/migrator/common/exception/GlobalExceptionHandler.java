package com.spotifyyoutube.migrator.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParams(org.springframework.web.bind.MissingServletRequestParameterException ex, HttpServletRequest request) {
        ApiError apiError = new ApiError(
                ErrorCode.INVALID_REQUEST,
                "Missing required parameter: " + ex.getParameterName(),
                Instant.now().toString(),
                request.getRequestURI()
        );
        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }


    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidationException(MethodArgumentNotValidException ex, HttpServletRequest request) {
        ApiError apiError = new ApiError(
                ErrorCode.INVALID_REQUEST,
                "Validation failed",
                Instant.now().toString(),
                request.getRequestURI()
        );
        return new ResponseEntity<>(apiError, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleResourceNotFoundException(ResourceNotFoundException ex, HttpServletRequest request) {
        ApiError apiError = new ApiError(
                ErrorCode.NOT_FOUND,
                ex.getMessage(),
                Instant.now().toString(),
                request.getRequestURI()
        );
        return new ResponseEntity<>(apiError, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflictException(ConflictException ex, HttpServletRequest request) {
        ApiError apiError = new ApiError(
                ErrorCode.CONFLICT,
                ex.getMessage(),
                Instant.now().toString(),
                request.getRequestURI()
        );
        return new ResponseEntity<>(apiError, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(InvalidStateException.class)
    public ResponseEntity<ApiError> handleInvalidStateException(InvalidStateException ex, HttpServletRequest request) {
        ApiError apiError = new ApiError(
                ErrorCode.INVALID_STATE,
                ex.getMessage(),
                Instant.now().toString(),
                request.getRequestURI()
        );
        return new ResponseEntity<>(apiError, HttpStatus.CONFLICT); // usually 409 Conflict for invalid state preventing an operation
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGenericException(Exception ex, HttpServletRequest request) {
        ApiError apiError = new ApiError(
                ErrorCode.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred",
                Instant.now().toString(),
                request.getRequestURI()
        );
        return new ResponseEntity<>(apiError, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
