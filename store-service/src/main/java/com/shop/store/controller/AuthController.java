package com.shop.store.controller;

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

    /**
     * Validates local credentials and returns a Basic header, or HTTP 401 for incorrect credentials.
     */
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
