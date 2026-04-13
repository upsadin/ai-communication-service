package com.aicomm.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI Communication Service API")
                        .description("""
                                API для управления AI HR-агентом.

                                **Personas** — управление HR-персонами (промпты, шаблоны сообщений, тестовые задания)

                                **Conversations** — просмотр диалогов с кандидатами

                                **Telegram** — авторизация и управление Telegram-клиентом
                                """)
                        .version("1.0.0")
                        .contact(new Contact().name("LuxTech")));
    }
}
