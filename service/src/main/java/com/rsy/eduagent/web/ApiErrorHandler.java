package com.rsy.eduagent.web;

import com.rsy.eduagent.chat.ChatModels;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiErrorHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ChatModels.Error> known(ApiException error) {
        return ResponseEntity.status(error.status()).body(new ChatModels.Error(error.code(), error.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ChatModels.Error> invalid(Exception ignored) {
        return ResponseEntity.badRequest().body(new ChatModels.Error("INVALID_REQUEST", "Check question, userId and conversationId"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ChatModels.Error> internal(Exception ignored) {
        return ResponseEntity.internalServerError().body(new ChatModels.Error("INTERNAL_ERROR", "Request could not be completed"));
    }
}
