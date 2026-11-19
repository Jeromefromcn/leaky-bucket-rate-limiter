package com.example.ratelimiter.web;

import com.example.ratelimiter.service.InvalidUserIdException;
import com.example.ratelimiter.web.dto.ApiError;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BucketNotFoundException.class)
    ResponseEntity<ApiError> notFound(BucketNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("not_found", exception.getMessage()));
    }

    @ExceptionHandler(InvalidUserIdException.class)
    ResponseEntity<ApiError> invalidUserId(InvalidUserIdException exception) {
        return ResponseEntity.badRequest()
                .body(new ApiError("invalid_user_id", exception.getMessage()));
    }
}
