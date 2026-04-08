package com.aicomm.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "telegram")
public record TelegramProperties(
        int apiId,
        String apiHash,
        String phone,
        String databaseEncryptionKey,
        String sessionPath
) {
}
