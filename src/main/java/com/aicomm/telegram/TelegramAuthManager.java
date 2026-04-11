package com.aicomm.telegram;

import com.aicomm.config.TelegramProperties;
import it.tdlight.Init;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.ClientInteraction;
import it.tdlight.client.InputParameter;
import it.tdlight.client.ParameterInfo;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.client.APIToken;
import it.tdlight.jni.TdApi;
import jakarta.annotation.PreDestroy;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages TDLib client lifecycle: initialization, authentication (OTP), shutdown.
 *
 * OTP flow: TDLib calls ClientInteraction.onParameterRequest(ASK_CODE, ...)
 * → we return a CompletableFuture that completes when user submits code via REST.
 */
@Slf4j
@Component
@EnableConfigurationProperties(TelegramProperties.class)
public class TelegramAuthManager {

    private final SimpleTelegramClientFactory clientFactory;

    @Getter
    private final SimpleTelegramClient client;

    @Getter
    private final AtomicReference<AuthState> authState = new AtomicReference<>(AuthState.INITIALIZING);

    /** Completed by submitOtpCode() when user sends the code via REST endpoint. */
    private volatile CompletableFuture<String> otpCodeFuture = new CompletableFuture<>();

    public enum AuthState {
        INITIALIZING, WAITING_CODE, WAITING_PASSWORD, READY, ERROR, CLOSED
    }

    public TelegramAuthManager(TelegramProperties properties,
                               TelegramUpdateHandler updateHandler) throws Exception {
        // Pre-load native dependencies before TDLib (Windows needs this)
        preloadNativeDeps();

        // Initialize TDLib native libraries
        Init.init();

        // Configure TDLib settings
        var apiToken = new APIToken(properties.apiId(), properties.apiHash());
        var settings = TDLibSettings.create(apiToken);
        var sessionPath = Path.of(properties.sessionPath());
        settings.setDatabaseDirectoryPath(sessionPath.resolve("data"));
        settings.setDownloadedFilesDirectoryPath(sessionPath.resolve("downloads"));

        // Build client
        this.clientFactory = new SimpleTelegramClientFactory();
        SimpleTelegramClientBuilder clientBuilder = clientFactory.builder(settings);

        // Custom interaction handler — OTP code is provided via REST, not console
        clientBuilder.setClientInteraction(new ClientInteraction() {
            @Override
            public CompletableFuture<String> onParameterRequest(InputParameter parameter, ParameterInfo parameterInfo) {
                return switch (parameter) {
                    case ASK_CODE -> {
                        log.info("TDLib requesting OTP code. Submit via POST /internal/telegram/otp");
                        authState.set(AuthState.WAITING_CODE);
                        yield otpCodeFuture;
                    }
                    case ASK_PASSWORD -> {
                        log.warn("TDLib requesting 2FA password. Submit via POST /internal/telegram/otp");
                        authState.set(AuthState.WAITING_PASSWORD);
                        // Reuse the same future mechanism for password
                        otpCodeFuture = new CompletableFuture<>();
                        yield otpCodeFuture;
                    }
                    case ASK_FIRST_NAME -> CompletableFuture.completedFuture("AI");
                    case ASK_LAST_NAME -> CompletableFuture.completedFuture("Service");
                    default -> {
                        log.debug("Ignoring parameter request: {}", parameter);
                        yield CompletableFuture.completedFuture("");
                    }
                };
            }
        });

        // Register handler for incoming messages
        clientBuilder.addUpdateHandler(TdApi.UpdateNewMessage.class, updateHandler::onUpdateNewMessage);

        // Monitor auth state transitions
        clientBuilder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, update -> {
            switch (update.authorizationState) {
                case TdApi.AuthorizationStateReady ignored -> {
                    log.info("Telegram authorization successful — client is READY");
                    authState.set(AuthState.READY);
                }
                case TdApi.AuthorizationStateClosed ignored -> {
                    log.info("Telegram client closed");
                    authState.set(AuthState.CLOSED);
                }
                case TdApi.AuthorizationStateClosing ignored ->
                        log.info("Telegram client is closing...");
                default ->
                        log.debug("Auth state: {}", update.authorizationState.getClass().getSimpleName());
            }
        });

        // Build and start — uses phone number auth
        this.client = clientBuilder.build(AuthenticationSupplier.user(properties.phone()));

        log.info("TDLib client initialized. Session path: {}", sessionPath.toAbsolutePath());
    }

    /**
     * Called by TelegramAuthController when user submits OTP code or 2FA password.
     */
    public void submitOtpCode(String code) {
        log.info("OTP/password submitted via REST");
        otpCodeFuture.complete(code);
    }

    public boolean isReady() {
        return authState.get() == AuthState.READY;
    }

    /**
     * Pre-load native libraries that tdjni.dll depends on.
     * On Windows, JNI extraction to temp dir breaks implicit DLL resolution.
     * Loading deps explicitly first makes them available for tdjni.
     */
    private void preloadNativeDeps() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        String[] libs = {"libcrypto-3-x64", "libssl-3-x64", "zlib1"};
        for (String lib : libs) {
            try {
                System.loadLibrary(lib);
                log.debug("Pre-loaded native dependency: {}", lib);
            } catch (UnsatisfiedLinkError e) {
                log.debug("Could not pre-load {} (may not be needed): {}", lib, e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down TDLib client...");
        if (client != null) {
            client.sendClose();
        }
        if (clientFactory != null) {
            try {
                clientFactory.close();
            } catch (Exception e) {
                log.warn("Error closing TDLib client factory", e);
            }
        }
    }
}
