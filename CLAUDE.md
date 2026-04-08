# AI Communication Service

## Project Rules

### 1. Strict Security
- **Никаких секретов в коде**: номер телефона, api_id, api_hash, пароли БД, database_encryption_key для TDLib — ТОЛЬКО в переменных окружения или `application-local.yml`
- **Изоляция сессии**: папка `tdlib-session/` в `.gitignore`. Никогда не коммитить
- **Логирование**: никогда не выводить в логи полные номера телефонов кандидатов или секьюрные токены

### 2. Architecture & Code (Spring Boot 3.4 & Java 21)
- **Современная Java**: Records для DTO, Pattern Matching, Switch expressions
- **Слабая связность**: TDLib-клиент не знает про LangChain4j или Kafka. Взаимодействие через интерфейсы и Spring ApplicationEvents
- **Тестируемость**: код покрываем unit-тестами через Mockito. Без жёстких зависимостей и статических методов где нужна подмена

### 3. TDLib Specifics
- **Асинхронность**: TDLib работает асинхронно. Не блокировать основной поток Spring Boot
- **Нативные библиотеки**: корректная загрузка .so/.dll для стабильного запуска локально (Windows) и в Docker (Linux)

### 4. Workflow
- **Маленькие шаги**: базовый сетап → проверка запуска → OTP → эхо-ответ → БД
- **Самодокументация**: после каждого завершённого шага обновлять этот файл
- **Спрашивай разрешение**: перед изменением pom.xml, application.yml или выполнением команд — показать что именно добавляется

---

## Tech Stack

- Java 21, Spring Boot 3.4+
- Spring Data JPA (PostgreSQL), Spring Kafka
- LangChain4j 1.0.0-beta1 (OpenAI → Ollama)
- tdlight-java (TDLib MTProto wrapper)
- Flyway (DB migrations)

---

## Architecture

```
Kafka: ai.processing.filtered (MessageProcessingTask: ref, sourceId, aiResult)
  |
  v
MessageTaskConsumer (@KafkaListener, AckMode.MANUAL)
  |
  v
IdempotencyService.isAlreadyProcessed(sourceId)
  -> YES: ack + return
  -> NO:
      PersonaService.getByRef(ref) -> Persona (DB + @Cacheable)
        |
      CommunicationAgentFactory.createAgent(persona, sourceId)
        |-- AiServices.builder + systemMessageProvider + ChatMemory (DB)
        |
      agent.chat(sourceId, context) -> generated message
        |
      TelegramClientService.sendMessage(contact, text)
        |
      ConversationService.save(conversation + messages)
        |
      IdempotencyService.markProcessed(sourceId)
      ack.acknowledge()

[Async - incoming replies]
TelegramUpdateHandler -> ConversationContinuationService
  -> load history from DB -> agent.chat -> send reply -> save
```

### Key Decisions
1. **AiResult** — raw JsonNode в MessageProcessingTask. Структура зависит от schemaJson per ref в upstream сервисе
2. **Personas в БД** — таблица `personas` + `@Cacheable`. Промпты меняются без редеплоя
3. **AI-сессии: LangChain4j + PostgreSQL** — портабельно, при переходе на Ollama меняется только бин ChatLanguageModel
4. **Идемпотентность** — таблица `processed_messages` с UNIQUE(source_id)
5. **Telegram: tdlight-java (MTProto)** — инициация диалогов от реального аккаунта
6. **Мультиканальность** — conversations хранит channel_type + contact_id (не только Telegram)

---

## Project Structure

```
ai-communication-service/
├── pom.xml
├── CLAUDE.md
├── FLOW.md
├── docker-compose.yml
├── .gitignore
└── src/
    ├── main/
    │   ├── java/com/aicomm/
    │   │   ├── AiCommunicationServiceApplication.java
    │   │   ├── config/
    │   │   │   └── TelegramProperties.java
    │   │   ├── controller/
    │   │   │   ├── TelegramAuthController.java
    │   │   │   ├── PersonaAdminController.java
    │   │   │   └── ConversationAdminController.java
    │   │   ├── telegram/
    │   │   │   ├── TelegramAuthManager.java
    │   │   │   ├── TelegramClientService.java
    │   │   │   ├── TelegramUpdateHandler.java
    │   │   │   └── TelegramRateLimiter.java
    │   │   ├── kafka/
    │   │   │   ├── MessageTaskConsumer.java
    │   │   │   └── dto/
    │   │   │       └── MessageProcessingTask.java
    │   │   ├── persona/
    │   │   │   └── PersonaService.java
    │   │   ├── agent/
    │   │   │   ├── CommunicationAgent.java
    │   │   │   └── CommunicationAgentFactory.java
    │   │   ├── conversation/
    │   │   │   ├── ConversationService.java
    │   │   │   ├── ConversationContinuationService.java
    │   │   │   ├── FirstContactService.java
    │   │   │   ├── ScheduledFollowUpService.java
    │   │   │   └── memory/
    │   │   │       └── JpaChatMemoryStore.java
    │   │   ├── schedule/
    │   │   │   ├── WorkScheduleService.java
    │   │   │   ├── WorkScheduleProperties.java
    │   │   │   ├── DeferredMessageService.java
    │   │   │   └── DeferredTaskExecutor.java
    │   │   ├── idempotency/
    │   │   │   └── IdempotencyService.java
    │   │   ├── util/
    │   │   │   └── MaskingUtil.java
    │   │   ├── domain/
    │   │   │   ├── ProcessedMessage.java
    │   │   │   ├── Conversation.java
    │   │   │   ├── ConversationMessage.java
    │   │   │   ├── Persona.java
    │   │   │   ├── DeferredTask.java
    │   │   │   ├── ConversationStatus.java
    │   │   │   └── ChannelType.java
    │   │   └── repository/
    │   │       ├── ProcessedMessageRepository.java
    │   │       ├── ConversationRepository.java
    │   │       ├── ConversationMessageRepository.java
    │   │       ├── PersonaRepository.java
    │   │       └── DeferredTaskRepository.java
    │   └── resources/
    │       ├── application.yml
    │       ├── application-local.yml          # секреты, .gitignore
    │       └── db/migration/
    │           ├── V1__create_processed_messages.sql
    │           ├── V2__create_conversations.sql
    │           ├── V3__create_conversation_messages.sql
    │           └── V4__create_personas.sql
    └── test/java/com/aicomm/service/
        └── ...
```

---

## DB Schema

```sql
-- V1: Idempotency
CREATE TABLE processed_messages (
    id BIGSERIAL PRIMARY KEY,
    source_id VARCHAR(255) NOT NULL UNIQUE,
    processed_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- V2: Conversations (multichannel)
CREATE TABLE conversations (
    id BIGSERIAL PRIMARY KEY,
    source_id VARCHAR(255) NOT NULL UNIQUE,
    ref VARCHAR(100) NOT NULL,
    full_name VARCHAR(500),
    channel_type VARCHAR(50) NOT NULL,
    contact_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'INITIATED',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- V3: Messages
CREATE TABLE conversation_messages (
    id BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT NOT NULL REFERENCES conversations(id),
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_conv_msg_conv_id ON conversation_messages(conversation_id, created_at);

-- V4: Personas
CREATE TABLE personas (
    id BIGSERIAL PRIMARY KEY,
    ref VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(255),
    system_prompt TEXT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```

---

## Kafka Config (Phase 3)

| Parameter | Value | Reason |
|---|---|---|
| enable.auto.commit | false | Manual ack only |
| isolation.level | read_committed | Skip uncommitted |
| auto.offset.reset | earliest | Safe — idempotency via DB |
| max.poll.records | 1 | Atomic processing |
| AckMode | MANUAL | Offset after DB commit |

---

## Development Status

### Phase 1 — Telegram Starter [DONE]
- [x] pom.xml (tdlight-java 3.4.4+td.1.8.52, BOM, natives for Win+Linux)
- [x] .gitignore (tdlib-session/, application-local.yml)
- [x] application.yml (secrets via env vars)
- [x] application-local.yml (user created with real credentials)
- [x] AiCommunicationServiceApplication.java
- [x] TelegramProperties.java (record, @ConfigurationProperties)
- [x] TelegramAuthManager.java (ClientInteraction for OTP, preloadNativeDeps for Windows)
- [x] TelegramClientService.java (sendByUsername, sendByChatId, human delay 2-5s)
- [x] TelegramUpdateHandler.java (incoming message listener, logs incoming)
- [x] TelegramAuthController.java (POST /otp, GET /status, POST /test-send)
- [x] BUILD SUCCESS — mvn compile passes
- [x] VERIFIED: TDLib init OK, OTP OK, send to @vikulitko OK, receive reply OK
- Note: Windows requires zlib1__.dll and OpenSSL 3 in System32

### Phase 2 — Domain & Idempotency [DONE]
- [x] docker-compose.yml (PostgreSQL 16 Alpine, port 5433)
- [x] pom.xml (spring-data-jpa, postgresql, flyway)
- [x] application.yml (datasource, jpa validate, flyway)
- [x] Flyway migrations V1-V4 (processed_messages, conversations, conversation_messages, personas)
- [x] JPA entities: ProcessedMessage, Conversation, ConversationMessage, Persona
- [x] Enums: ConversationStatus, ChannelType
- [x] Repositories: ProcessedMessageRepository, ConversationRepository, ConversationMessageRepository, PersonaRepository
- [x] IdempotencyService (two-layer: existsBy + UNIQUE constraint)
- [x] Seed data: 2 personas (candidate_java, candidate_go)
- [x] VERIFIED: Flyway migrations applied, tables created, app starts with READY status

### Phase 3 — Kafka Consumer [DONE]
- [x] docker-compose.yml (Kafka KRaft 3.7.0, port 29092)
- [x] pom.xml (spring-kafka)
- [x] application.yml (kafka config: manual ack, read_committed, max.poll.records=1)
- [x] MessageProcessingTask record (ref, sourceId, AiResult{fullName, contactId, channelType, context})
- [x] MessageTaskConsumer (@KafkaListener, idempotency check, manual ack)
- [x] VERIFIED: consume from topic OK, idempotency dedup OK, processed_messages in DB OK

### Phase 4 — Personas (DB + Cache) [DONE]
- [x] PersonaService (@Cacheable, evictByRef, evictAll)
- [x] @EnableCaching in Application
- [x] Seed data: 2 personas with real system prompts (Russian, human-like recruiter)
- [x] Renamed display_name → label (in V4, clean DB recreate)
- [x] VERIFIED: personas in DB with correct prompts

### Phase 5 — LangChain4j + Dialog [DONE]
- [x] pom.xml (langchain4j 1.0.0-beta1, open-ai starter)
- [x] application.yml (OpenAI config, typing params, agent memory — all via env vars)
- [x] CommunicationAgent (LangChain4j @AiService, dynamic system prompt via @V)
- [x] CommunicationAgentFactory (chatMemoryProvider per conversationId, DB-backed)
- [x] JpaChatMemoryStore (loads history from conversation_messages, updateMessages no-op)
- [x] ConversationService (create, addMessage via MessageRepository, updateStatus, findActive)
- [x] FirstContactService (парсинг aiResult, рендеринг шаблона из Persona, AI → Telegram)
- [x] ConversationContinuationService (@Async, incoming replies: classify + AI → Telegram)
- [x] TelegramClientService (read delay + typing indicator с refresh каждые 4с + retry)
- [x] TelegramUpdateHandler (парсинг всех типов контента, markAsRead, делегирование)
- [x] MessageTaskConsumer (thin: Kafka → idempotency → deferNow в очередь)
- [x] WorkScheduleService (online/offline по расписанию 09:00-18:35, isWorkingHours)
- [x] DeferredMessageService (сохранение задач в БД: defer, deferNow, deferWithDelay)
- [x] DeferredTaskExecutor (poll каждую минуту, FIRST_CONTACT/REPLY/FOLLOW_UP, retry 3 попытки)
- [x] ScheduledFollowUpService (AI-классификатор "напиши позже", follow-up через deferred_tasks)
- [x] TelegramRateLimiter (интервал + дневной лимит для первых контактов)
- [x] @EnableAsync, @EnableScheduling, @EnableCaching
- [x] Flyway V5 (deferred_tasks с индексом по status+execute_at)
- [x] first_message_template в Persona (шаблон из БД с {{плейсхолдерами}})
- [x] BUILD SUCCESS + 55 unit-тестов

### Phase 6 — Hardening & Production Readiness [IN PROGRESS]

#### Критичное
- [x] Отложенные сообщения в БД (deferred_tasks) — переживает рестарт Heroku
- [x] "Напиши позже" — AI-классификатор + FOLLOW_UP в deferred_tasks
- [ ] Завершение диалога — AI определяет конец → статус COMPLETED
- [ ] Heroku deployment (Dockerfile, S3 для TDLib-сессии, конфиги)

#### Важное
- [x] Unit-тесты — 55 тестов (Mockito: все ключевые сервисы + контроллеры)
- [x] Retry Telegram — exponential backoff (3 попытки, 2с→4с→8с, только transient ошибки)
- [x] Rate limiting — TelegramRateLimiter (120с интервал, 25/день), FIRST_CONTACT всегда через очередь
- [ ] Integration-тесты (Testcontainers: Postgres + Kafka)
- [ ] Kafka consumer parallelism (партиции + concurrency для 10 RPS)
- [ ] Обратная связь в upstream — статус диалога обратно в Kafka

#### Улучшения
- [x] Privacy — MaskingUtil (phone, contactId, name, chatId, truncate), применён во всех логах
- [x] Админ API — PersonaAdminController (CRUD + cache evict), ConversationAdminController (list + detail + status filter)
- [x] Все параметры через env vars (ни одного захардкоженного секрета)
- [ ] README.md с инструкцией: env vars, docker-compose, OTP, endpoints
- [ ] Мониторинг (Actuator + метрики)
- [ ] S3 синхронизация TDLib-сессии (для Heroku)
