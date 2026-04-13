package com.aicomm.controller;

import com.aicomm.telegram.TelegramAuthManager;
import com.aicomm.telegram.TelegramClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Slf4j
@RestController
@RequestMapping("/internal/telegram")
@RequiredArgsConstructor
@Tag(name = "Telegram", description = "Управление Telegram-клиентом: авторизация (OTP), статус, тестовая отправка")
public class TelegramAuthController {

    private final TelegramAuthManager authManager;
    private final TelegramClientService clientService;

    @PostMapping("/otp")
    @Operation(summary = "Отправить OTP-код",
            description = "Передаёт код авторизации (из SMS/Telegram) в TDLib. Используется при первичной настройке.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(examples = @ExampleObject(value = """
                    {"code": "12345"}""")))
    @ApiResponse(responseCode = "200", description = "Код принят")
    public ResponseEntity<Map<String, String>> submitOtp(@RequestBody OtpRequest request) {
        log.info("OTP code received via REST endpoint");
        authManager.submitOtpCode(request.code());
        return ResponseEntity.ok(Map.of(
                "status", "submitted",
                "message", "OTP code submitted. Check /internal/telegram/status for result."
        ));
    }

    @GetMapping("/status")
    @Operation(summary = "Статус Telegram-клиента",
            description = "Возвращает текущее состояние авторизации: INITIALIZING, WAITING_CODE, WAITING_PASSWORD, READY, ERROR, CLOSED")
    @ApiResponse(responseCode = "200", description = "Текущий статус",
            content = @Content(examples = @ExampleObject(value = """
                    {"status": "READY"}""")))
    public ResponseEntity<Map<String, String>> getStatus() {
        var state = authManager.getAuthState().get();
        return ResponseEntity.ok(Map.of("status", state.name()));
    }

    @PostMapping("/test-send")
    @Operation(summary = "Тестовая отправка сообщения",
            description = "Отправляет сообщение по @username через Telegram. Только для тестирования.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(examples = @ExampleObject(value = """
                    {"username": "testuser", "text": "Привет, это тестовое сообщение!"}""")))
    @ApiResponse(responseCode = "200", description = "Сообщение отправлено")
    @ApiResponse(responseCode = "500", description = "Ошибка отправки")
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

    @Schema(description = "OTP-код для авторизации в Telegram")
    public record OtpRequest(
            @Schema(description = "Код из SMS или Telegram", example = "12345") String code) {}

    @Schema(description = "Запрос на тестовую отправку")
    public record TestSendRequest(
            @Schema(description = "Telegram @username получателя", example = "testuser") String username,
            @Schema(description = "Текст сообщения", example = "Привет!") String text) {}
}
