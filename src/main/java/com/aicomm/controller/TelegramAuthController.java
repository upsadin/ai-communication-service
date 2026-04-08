package com.aicomm.controller;

import com.aicomm.telegram.TelegramAuthManager;
import com.aicomm.telegram.TelegramClientService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Internal controller for Telegram authentication and testing.
 * NOT a public API — used only during initial setup and dev testing.
 *
 * Endpoints:
 *   POST /internal/telegram/otp         — submit OTP code for first-time auth
 *   GET  /internal/telegram/status       — check current auth state
 *   POST /internal/telegram/test-send    — send a test message (dev only)
 */
@Slf4j
@RestController
@RequestMapping("/internal/telegram")
@RequiredArgsConstructor
public class TelegramAuthController {

    private final TelegramAuthManager authManager;
    private final TelegramClientService clientService;

    /**
     * Submit OTP code received via SMS/Telegram.
     * Used once during first-time authorization.
     */
    @PostMapping("/otp")
    public ResponseEntity<Map<String, String>> submitOtp(@RequestBody OtpRequest request) {
        log.info("OTP code received via REST endpoint");
        authManager.submitOtpCode(request.code());
        return ResponseEntity.ok(Map.of(
                "status", "submitted",
                "message", "OTP code submitted. Check /internal/telegram/status for result."
        ));
    }

    /**
     * Check current Telegram authorization state.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, String>> getStatus() {
        var state = authManager.getAuthState().get();
        return ResponseEntity.ok(Map.of("status", state.name()));
    }

    /**
     * Send a test message to a username. Dev/testing only.
     */
    @PostMapping("/test-send")
    public CompletableFuture<ResponseEntity<Map<String, String>>> testSend(@RequestBody TestSendRequest request) {
        log.info("Test send to @{}", request.username());
        return clientService.sendMessageByUsername(request.username(), request.text())
                .thenApply(msg -> ResponseEntity.ok(Map.of(
                        "status", "sent",
                        "chatId", String.valueOf(msg.chatId)
                )))
                .exceptionally(ex -> ResponseEntity.internalServerError().body(Map.of(
                        "status", "error",
                        "message", ex.getMessage()
                )));
    }

    public record OtpRequest(String code) {}
    public record TestSendRequest(String username, String text) {}
}
