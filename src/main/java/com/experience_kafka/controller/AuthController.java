package com.experience_kafka.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    @PostMapping("/auth")
    public ResponseEntity<Map<String, String>> authenticate(@RequestBody AuthRequest request) {
        if ("user".equals(request.username()) && "qwerty".equals(request.password())) {
            String token = Base64.getEncoder()
                    .encodeToString((request.username() + ":" + request.password()).getBytes(StandardCharsets.UTF_8));
            return ResponseEntity.ok(Map.of("token", "Basic " + token));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    public record AuthRequest(String username, String password) { }
}
