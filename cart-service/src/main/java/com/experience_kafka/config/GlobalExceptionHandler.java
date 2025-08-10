package com.experience_kafka.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.http.HttpStatus;

@ControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    @ResponseBody
    public ResponseEntity<String> handleException(Exception ex) {
        if (ex instanceof ResponseStatusException rse) {
            return ResponseEntity.status(rse.getStatusCode()).body(rse.getReason());
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: " + ex.getMessage());
    }
}
