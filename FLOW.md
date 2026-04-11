# AI Communication Service — Полное описание флоу

## Общая схема

```
Kafka (ai.processing.filtered)
  │
  ▼
MessageTaskConsumer          ── Kafka receive, idempotency, ack
  │                              Всегда ставит FIRST_CONTACT в очередь (deferNow)
  ▼
deferred_tasks (PostgreSQL)  ── Очередь задач, переживает рестарт
  │
  ▼
DeferredTaskExecutor         ── Poll каждую минуту (только в рабочее время)
  │                              FIRST_CONTACT → TelegramRateLimiter (120с, 25/день)
  │                              REPLY/FOLLOW_UP → без лимита
  ▼
FirstContactService          ── Парсинг aiResult, рендеринг шаблона из Persona,
  │                              создание диалога, AI → Telegram
  ▼
TelegramClientService        ── Read delay (3-15с) → Typing (120мс/символ) → Send
  │                              Retry: 3 попытки, exponential backoff
  ▼
Telegram (кандидат получает сообщение)
  │
  │  ... кандидат отвечает ...
  │
  ▼
TelegramUpdateHandler        ── TDLib callback, парсинг контента, markAsRead
  │
  ▼
ConversationContinuationService  ── @Async, поиск активного диалога
  │                                  ScheduledFollowUpService.analyzeAndScheduleIfNeeded()
  │                                  (AI-классификатор: "напиши позже?" → deferred_tasks)
  ▼
DeferredMessageService       ── Проверка рабочего времени (09:00-18:35)
  │                              Рабочее → executeOrDefer → генерация сразу
  │                              Нерабочее → сохранить REPLY в deferred_tasks
  │
  ▼
AI Agent (LangChain4j)       ── Генерация ответа с контекстом из БД
  │                              @Tool: sendTestTask, notifyTeam, closeConversation
  │                              ConversationTools + ThreadLocal ConversationContext
  ▼
TelegramClientService        ── Read delay → Typing indicator → Send

[Test Task Timeout]
DeferredTaskExecutor         ── Poll: TEST_SENT + N дней без ответа
  │                              → AI-напоминание кандидату
  │                              → ещё 1 день → TIMED_OUT
```

---

## 1. Получение задачи из Kafka

### `MessageTaskConsumer.consume(record, ack)`

**Класс:** `com.aicomm.kafka.MessageTaskConsumer`
**Аннотация:** `@KafkaListener(topics = "${app.kafka.topic.message-tasks}")`
**Kafka config:** manual ack, read_committed, max.poll.records=1

**Входные данные:**
- `ConsumerRecord<String, MessageProcessingTask>` из топика `ai.processing.filtered`
- `MessageProcessingTask` — Java record:
  - `String ref` — идентификатор персоны (например `"candidate_java"`)
  - `String sourceId` — уникальный ID для идемпотентности
  - `JsonNode aiResult` — результат AI-анализа из upstream сервиса (структура зависит от ref)

**Логика:**
1. Логирует получение: `sourceId`, `ref`
2. Вызывает `IdempotencyService.isAlreadyProcessed(sourceId)` — SELECT по UNIQUE индексу
3. Если дубликат → `ack.acknowledge()` + return (offset сдвигается, сообщение не обрабатывается)
4. Если новое → `idempotencyService.markProcessed(sourceId)` — INSERT в `processed_messages`
5. `ack.acknowledge()` — **сразу сдвигаем offset** (обработка может быть отложена)
6. Делегирует в `deferredMessageService.executeOrDefer(() -> firstContactService.initiateContact(task))`

**Почему ack сразу:** если сообщение пришло в нерабочее время, оно откладывается в in-memory scheduler. Offset нужно сдвинуть, чтобы при перезапуске не обработать повторно. Идемпотентность уже записана в БД — повторная доставка будет отклонена.

---

## 2. Проверка рабочего времени

### `DeferredMessageService.executeOrDefer(task, description)`

**Класс:** `com.aicomm.schedule.DeferredMessageService`

**Логика:**
1. Вызывает `workScheduleService.isWorkingHours()`
2. **Рабочее время** → `task.run()` — выполняет немедленно, возвращает `true`
3. **Нерабочее время** → вычисляет задержку, планирует в `ScheduledExecutorService`, возвращает `false`

### `WorkScheduleService.isWorkingHours()`

**Класс:** `com.aicomm.schedule.WorkScheduleService`

**Логика:**
1. Получает текущее время в таймзоне из конфига (`Europe/Moscow`)
2. Проверяет день недели — входит ли в `workDays` (пн-пт)
3. Проверяет время — между `workStart` (09:00) и `workEnd` (18:35)
4. Оба условия true → рабочее время

### `WorkScheduleService.calculateDeferralDelay()`

**Логика:**
1. Если сейчас рабочее время → возвращает 0
2. Иначе находит следующий рабочий день:
   - Если сегодня рабочий день и ещё не наступило `workStart` → используем сегодня
   - Иначе ищем следующий день из `workDays` (пропускает выходные)
3. Вычисляет мс до `workStart` следующего рабочего дня
4. Добавляет рандом от `morningDelayMinMs` (1 мин) до `morningDelayMaxMs` (30 мин)
5. Возвращает суммарную задержку

**Пример:** пятница 20:00 → следующий рабочий день = понедельник 09:00 → ~61ч + рандом 1-30 мин

---

## 3. Онлайн-статус Telegram

### `WorkScheduleService.checkAndToggleOnlineStatus()`

**Аннотация:** `@Scheduled(fixedRate = 60_000)` — каждую минуту

**Логика:**
1. Проверяет `authManager.isReady()` — TDLib авторизован?
2. Вызывает `isWorkingHours()`
3. Отправляет `TdApi.SetOption("online", boolean)` в TDLib
4. 09:00 → online=true (аккаунт "в сети")
5. 18:35 → online=false (аккаунт "был недавно")

**Для собеседника:** HR-аккаунт появляется в сети в рабочее время и уходит вечером, как реальный сотрудник.

---

## 4. Первый контакт с кандидатом

### `FirstContactService.initiateContact(task)`

**Класс:** `com.aicomm.conversation.FirstContactService`

**Шаги:**

### 4.1 Поиск персоны

```java
personaService.getByRef(task.ref())
```
- `PersonaService.getByRef()` — `@Cacheable("personas")`, ищет в БД по `ref` + `active=true`
- Возвращает `Persona`:
  - `systemPrompt` — характер и правила AI-агента
  - `firstMessageTemplate` — шаблон промпта первого сообщения с `{{плейсхолдерами}}`

### 4.2 Парсинг aiResult через AiResultFieldExtractor

```java
var extractor = new AiResultFieldExtractor(persona.getFieldMapping());
var contactId = extractor.extract(aiResult, "contactField");
var fullName   = extractor.extract(aiResult, "nameField");
```

**`AiResultFieldExtractor`** — утилитный класс в `com.aicomm.util`. Принимает JSON из поля `field_mapping` персоны и использует его как карту путей:

```json
{
  "nameField":    "full_name",
  "contactField": "contacts.telegram",
  "reasonField":  "reason"
}
```

- `extract(aiResult, "contactField")` → читает путь `"contacts.telegram"` из `field_mapping`, затем обходит `aiResult` по точечной нотации: `aiResult.contacts.telegram`
- Поддерживает вложенные пути произвольной глубины (dot-notation)
- Если путь не найден или значение `null` → возвращает `null`
- Структура `aiResult` не фиксирована — каждая персона описывает свою схему через `field_mapping`

### 4.3 Создание диалога

```java
conversationService.createConversation(sourceId, ref, fullName, channelType, contactId)
```

**`ConversationService.createConversation()`:**
- Создаёт `Conversation` entity
- Устанавливает `status = INITIATED`
- `@Transactional` — сохраняет в `conversations`
- Возвращает entity с присвоенным `id`

### 4.4 Рендеринг шаблона первого сообщения

```java
renderTemplate(persona, aiResult, fullName)
```

**`renderTemplate()`:**
1. Берёт `persona.getFirstMessageTemplate()` из БД, например:
   ```
   Напиши первое сообщение кандидату. Вот что ты о нём знаешь:
   - Имя: {{name}} (если имя латиницей — напиши его кириллицей)
   - Что зацепило: {{reasonFit}}
   Поздоровайся, коротко упомяни что заинтересовало и спроси, актуален ли поиск.
   ```
2. Заменяет `{{name}}` → fullName (или "кандидат" если null)
3. Заменяет `{{reasonFit}}` → результат `resolveReasonFit()`
4. Заменяет любые другие `{{fieldName}}` из aiResult автоматически (итерация по полям JsonNode)

**`resolveReasonFit(aiResult)`** — пробует поля в порядке приоритета:
1. `reasonFit`
2. `reason_fit` (snake_case вариант)
3. `context`
4. Весь aiResult как строка (fallback)

### 4.5 Сохранение промпта как USER-сообщение

```java
conversationService.addMessage(conversation, "USER", firstMessagePrompt)
```

**Зачем до вызова AI:** `JpaChatMemoryStore.getMessages()` загружает историю из `conversation_messages`. Если не сохранить промпт до вызова `agent.chat()` — memory будет пустой и LangChain4j выбросит ошибку.

**`ConversationService.addMessage()`:**
- Создаёт `ConversationMessage(conversation, role, content)`
- `@Transactional` — сохраняет через `ConversationMessageRepository`
- Не использует lazy-коллекцию `conversation.messages` (избегает `LazyInitializationException` в async-контексте)

### 4.6 Генерация AI-ответа

```java
var agent = agentFactory.createAgent(persona);
var aiResponse = agent.chat(conversationId, persona.getSystemPrompt(), firstMessagePrompt);
```

**`CommunicationAgentFactory.createAgent(persona)`:**
- Создаёт LangChain4j `AiServices.builder(CommunicationAgent.class)`
- Инжектирует `ChatLanguageModel` (OpenAI gpt-4o, автоконфигурация spring-boot-starter)
- Устанавливает `chatMemoryProvider` — для каждого `conversationId` создаёт `MessageWindowChatMemory`:
  - `maxMessages = 20` (скользящее окно, ~10 пар реплик)
  - `chatMemoryStore = JpaChatMemoryStore` (загрузка из БД)

**`CommunicationAgent.chat(conversationId, systemPrompt, userMessage)`:**
- Интерфейс с аннотациями:
  - `@SystemMessage("{{systemPrompt}}")` — промпт персоны, подставляется через `@V`
  - `@MemoryId Long conversationId` — ключ для ChatMemory
  - `@UserMessage String userMessage` — текст от пользователя (или промпт первого сообщения)
- LangChain4j:
  1. Вызывает `chatMemoryStore.getMessages(conversationId)` — загружает историю
  2. Добавляет system prompt + user message
  3. Отправляет в OpenAI API
  4. Возвращает текст ответа

**`JpaChatMemoryStore.getMessages(conversationId)`:**
- `messageRepository.findByConversationIdOrderByCreatedAtAsc(conversationId)`
- Конвертирует каждый `ConversationMessage` в LangChain4j `ChatMessage`:
  - `"USER"` → `UserMessage`
  - `"ASSISTANT"` → `AiMessage`
  - `"SYSTEM"` → `SystemMessage`

**`JpaChatMemoryStore.updateMessages()`** — **no-op**. Мы сохраняем сообщения вручную через `ConversationService.addMessage()`, чтобы контролировать момент сохранения и избежать дубликатов.

### 4.7 Сохранение ответа AI

```java
conversationService.addMessage(conversation, "ASSISTANT", aiResponse)
```

### 4.8 Отправка в Telegram

```java
telegramClientService.sendMessageByChatId(Long.parseLong(contactId), aiResponse)
    .thenRun(() -> conversationService.updateStatus(conversation, ACTIVE))
    .exceptionally(ex -> { updateStatus(conversation, FAILED); })
    .join();
```

- При успешной отправке → статус диалога `ACTIVE` (готов принимать ответы)
- При ошибке → статус `FAILED`
- `.join()` — блокирует до завершения отправки (нужно знать результат)

---

## 5. Имитация человеческого поведения при отправке

### `TelegramClientService.sendWithTyping(chatId, text)`

**Три фазы:**

### 5.1 Задержка "чтения" (read delay)

```java
delay(readDelayMs)  // рандом от 3с до 15с
```

- **Что видит собеседник:** ничего — HR "занят", ещё не прочитал / думает
- Конфигурация: `app.telegram.read-delay.min-ms`, `max-ms`

### 5.2 Индикатор печатания (typing)

```java
simulateTypingWithRefresh(chatId, typingDelayMs)
```

- Отправляет `TdApi.SendChatAction(chatId, ChatActionTyping)` — собеседник видит "печатает..."
- **Обновляет каждые 4 секунды** — Telegram сбрасывает индикатор через ~5с
- Длительность: `clamp(120мс × длина_ответа + рандом(1-3с), 3с, 55с)`

**`calculateTypingDelay(text)`:**
- `baseDelay = msPerChar (120) × text.length()`
- `jitter = random(1000, 3000)`
- `result = clamp(baseDelay + jitter, minTypingMs, maxTypingMs)`

**Примеры:**
| Ответ AI | Символов | Read delay | Typing | Итого |
|---|---|---|---|---|
| "Привет)" | 8 | ~9с | ~3с (min) | ~12с |
| "Видел профиль, интересно" | 50 | ~7с | ~8с | ~15с |
| Описание вакансии | 300 | ~12с | ~38с | ~50с |

### 5.3 Отправка

```java
sendTextMessage(chatId, text)
```

- `TdApi.SendMessage(chatId, InputMessageText(FormattedText(text)))`
- Асинхронный callback от TDLib → `CompletableFuture<TdApi.Message>`

---

## 6. Входящее сообщение от кандидата

### `TelegramUpdateHandler.onUpdateNewMessage(update)`

**Класс:** `com.aicomm.telegram.TelegramUpdateHandler`
**Вызывается из:** TDLib thread (зарегистрирован в `TelegramAuthManager`)

**Логика:**

### 6.1 Фильтрация

```java
if (message.isOutgoing) return;  // свои сообщения — пропускаем
```

### 6.2 Извлечение контента

```java
extractContentDescription(message.content)
```

Поддерживаемые типы:
| TDLib тип | ContentDescription |
|---|---|
| `MessageText` | `("text", "текст сообщения")` |
| `MessagePhoto` | `("photo", "[Фото] подпись")` или `("[Фото без подписи]")` |
| `MessageSticker` | `("sticker", "[Стикер: 😁]")` |
| `MessageAnimatedEmoji` | `("emoji", "👍")` |
| `MessageDocument` | `("document", "[Документ: file.pdf] подпись")` |
| `MessageVoiceNote` | `("voice", "[Голосовое сообщение] подпись")` |
| `MessageVideoNote` | `("video_note", "[Видео-кружок]")` |
| `MessageVideo` | `("video", "[Видео] подпись")` |
| `MessageAnimation` | `("animation", "[GIF] подпись")` |
| `MessageLocation` | `("location", "[Геолокация]")` |
| `MessageContact` | `("contact", "[Контакт: Имя Фамилия]")` |
| `MessagePoll` | `("poll", "[Опрос: вопрос]")` |
| Остальные | `null` → игнорируем |

**Зачем текстовое описание:** AI получает `"[Стикер: 😁]"` как обычный текст и реагирует по-человечески ("ха, класс)"). Не нужна специальная обработка медиа.

### 6.3 Отметка прочитанным

```java
markAsRead(chatId, messageId)
```

- `TdApi.ViewMessages(chatId, [messageId])` — у собеседника появляются синие галочки

### 6.4 Делегирование в async-сервис

```java
continuationService.handleIncomingReply(senderId, contentDescription.text())
```

- `@Async` — выполняется в пуле Spring, **не блокирует TDLib thread**
- TDLib thread свободен для приёма следующих сообщений

---

## 7. Продолжение диалога

### `ConversationContinuationService.handleIncomingReply(senderId, messageText)`

**Класс:** `com.aicomm.conversation.ConversationContinuationService`
**Аннотация:** `@Async`

### 7.1 Поиск активного диалога

```java
conversationService.findActiveByContact(TELEGRAM, String.valueOf(senderId))
```

- `ConversationRepository.findByChannelTypeAndContactIdAndStatus(TELEGRAM, contactId, ACTIVE)`
- Если нет активного диалога → `return` (сообщение от неизвестного, группы, канала — игнорируем)

### 7.2 Загрузка персоны

```java
personaService.getByRef(conversation.getRef())
```

- Из кэша (`@Cacheable`) — быстро

### 7.3 Сохранение входящего сообщения

```java
conversationService.addMessage(conversation, "USER", messageText)
```

- Сохраняется **сразу**, даже если ответ будет отложен — чтобы не потерять сообщение

### 7.4 Проверка рабочего времени

```java
deferredMessageService.executeOrDefer(
    () -> generateAndSendReply(conversation, persona, messageText),
    "reply:conv=" + conversation.getId()
)
```

- Рабочее время → генерируем и отправляем сразу
- Нерабочее → откладываем до утра + рандом

### 7.5 Генерация и отправка ответа

**`generateAndSendReply(conversation, persona, messageText)`:**

1. `agentFactory.createAgent(persona)` — создаёт агента
2. `agent.chat(conversationId, systemPrompt, messageText)` — генерация:
   - `JpaChatMemoryStore` загружает ВСЮ историю диалога из БД
   - AI видит: промпт первого сообщения + своё первое сообщение + все последующие реплики
   - Генерирует ответ в контексте всего разговора
3. `conversationService.addMessage(conversation, "ASSISTANT", aiResponse)` — сохраняет
4. `telegramClientService.sendMessageByChatId(contactId, aiResponse)` — отправляет с имитацией

---

## 8. Инициализация Telegram (при старте приложения)

### `TelegramAuthManager` (constructor)

**Класс:** `com.aicomm.telegram.TelegramAuthManager`
**Аннотация:** `@Component`

**При создании бина:**
1. `preloadNativeDeps()` — Windows: предзагрузка libcrypto, libssl, zlib (обход JNI-бага)
2. `Init.init()` — инициализация TDLib native libraries
3. Настройка `TDLibSettings` (apiId, apiHash, session path)
4. `SimpleTelegramClientBuilder`:
   - `setClientInteraction()` — обработка OTP: `ASK_CODE` → `CompletableFuture`, заполняется через REST
   - `addUpdateHandler(UpdateNewMessage, updateHandler::onUpdateNewMessage)` — регистрация callback
   - `addUpdateHandler(UpdateAuthorizationState, ...)` — мониторинг статуса авторизации
5. `clientBuilder.build(AuthenticationSupplier.user(phone))` — запуск с авторизацией по номеру

**Состояния:** `INITIALIZING → WAITING_CODE → WAITING_PASSWORD → READY → CLOSED`

### `TelegramAuthController`

- `POST /internal/telegram/otp` — передать OTP-код (или 2FA пароль)
- `GET /internal/telegram/status` — текущее состояние (`READY` / `WAITING_CODE` / ...)
- `POST /internal/telegram/test-send` — тестовая отправка сообщения

---

## 9. Схема данных

### Таблица `conversations`

| Поле | Тип | Описание |
|---|---|---|
| id | BIGSERIAL PK | |
| source_id | VARCHAR(255) UNIQUE | ID из Kafka для связи |
| ref | VARCHAR(100) | Ключ персоны |
| full_name | VARCHAR(500) | Имя кандидата |
| channel_type | VARCHAR(50) | TELEGRAM / WHATSAPP / EMAIL |
| contact_id | VARCHAR(255) | Telegram userId |
| status | VARCHAR(50) | INITIATED → ACTIVE → TEST_SENT → COMPLETED / FAILED / TIMED_OUT |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

### Таблица `conversation_messages`

| Поле | Тип | Описание |
|---|---|---|
| id | BIGSERIAL PK | |
| conversation_id | BIGINT FK | → conversations.id |
| role | VARCHAR(20) | USER / ASSISTANT / SYSTEM |
| content | TEXT | Текст сообщения |
| created_at | TIMESTAMP | |

Индекс: `idx_conv_msg_conv_id(conversation_id, created_at)`

### Таблица `personas`

| Поле | Тип | Описание |
|---|---|---|
| id | BIGSERIAL PK | |
| ref | VARCHAR(100) UNIQUE | Ключ (candidate_java, candidate_go, ...) |
| label | VARCHAR(255) | Внутренняя метка для логов/админки |
| system_prompt | TEXT | Промпт: характер, стиль, правила AI (включая инструкции по @Tool) |
| first_message_template | TEXT | Шаблон первого сообщения с {{плейсхолдерами}} |
| test_task_url | TEXT | Ссылка на тестовое задание (используется @Tool sendTestTask) |
| notification_contact | VARCHAR(255) | Telegram username (@user) или chatId для уведомлений команды |
| active | BOOLEAN | Активна ли персона |
| created_at | TIMESTAMP | |
| updated_at | TIMESTAMP | |

### Таблица `processed_messages`

| Поле | Тип | Описание |
|---|---|---|
| id | BIGSERIAL PK | |
| source_id | VARCHAR(255) UNIQUE | Kafka sourceId для идемпотентности |
| processed_at | TIMESTAMP | |

---

## 10. Конфигурация (application.yml)

### Kafka
```yaml
spring.kafka.bootstrap-servers          # Kafka broker
spring.kafka.consumer.group-id          # Consumer group
spring.kafka.consumer.auto-offset-reset # earliest
spring.kafka.consumer.enable-auto-commit # false (manual ack)
spring.kafka.consumer.isolation-level   # read_committed
spring.kafka.consumer.max-poll-records  # 1 (atomic processing)
spring.kafka.listener.ack-mode          # manual
app.kafka.topic.message-tasks           # Название топика
```

### OpenAI (LangChain4j)
```yaml
langchain4j.open-ai.chat-model.api-key      # API ключ
langchain4j.open-ai.chat-model.model-name    # gpt-4o
langchain4j.open-ai.chat-model.temperature   # 0.7
langchain4j.open-ai.chat-model.max-tokens    # 500
```

### Имитация поведения
```yaml
app.telegram.read-delay.min-ms    # 3000  — мин пауза перед "печатает"
app.telegram.read-delay.max-ms    # 15000 — макс пауза
app.telegram.typing.ms-per-char   # 120   — мс на символ при печатании
app.telegram.typing.min-typing-ms # 3000  — мин время печатания
app.telegram.typing.max-typing-ms # 55000 — макс время печатания
```

### Рабочее время
```yaml
app.schedule.work-start            # 09:00
app.schedule.work-end              # 18:35
app.schedule.timezone              # Europe/Moscow
app.schedule.work-days             # MONDAY-FRIDAY
app.schedule.morning-delay-min-ms  # 60000   (1 мин)
app.schedule.morning-delay-max-ms  # 1800000 (30 мин)
```

### Rate Limiting
```yaml
app.rate-limit.first-contact-interval-sec  # 120 (2 мин между первыми контактами)
app.rate-limit.max-first-contacts-per-day  # 25 (макс новых контактов в день)
```

### Deferred Tasks
```yaml
app.schedule.poll-interval-ms  # 60000 (poll каждую минуту)
```

### AI Agent
```yaml
app.agent.memory-max-messages  # 20 (скользящее окно истории)
```

---

## 11. Rate Limiting

### `TelegramRateLimiter`

**Класс:** `com.aicomm.telegram.TelegramRateLimiter`

Два уровня защиты от бана Telegram:
1. **Интервал** — минимум `first-contact-interval-sec` (120с) между первыми сообщениями новым контактам
2. **Дневной лимит** — максимум `max-first-contacts-per-day` (25) новых контактов в день

`acquireOrGetDelay()`:
- Возвращает `0` — можно отправить
- Возвращает `> 0` — нужно подождать N секунд
- Возвращает `-1` — дневной лимит исчерпан

Применяется ТОЛЬКО к FIRST_CONTACT. Ответы существующим контактам (REPLY, FOLLOW_UP) не ограничены.

---

## 12. Очередь отложенных задач

### Таблица `deferred_tasks`

| Поле | Тип | Описание |
|---|---|---|
| id | BIGSERIAL PK | |
| task_type | VARCHAR(50) | FIRST_CONTACT / REPLY / FOLLOW_UP |
| payload | TEXT (JSON) | Данные задачи |
| execute_at | TIMESTAMP | Когда выполнить |
| status | VARCHAR(20) | PENDING / COMPLETED / FAILED |
| attempts | INT | Количество попыток |
| error | TEXT | Текст последней ошибки |
| created_at | TIMESTAMP | |

Индекс: `idx_deferred_tasks_pending(status, execute_at) WHERE status = 'PENDING'`

### `DeferredTaskExecutor.pollAndExecute()`

Каждую минуту (только в рабочее время):
1. SELECT из deferred_tasks WHERE status='PENDING' AND execute_at <= NOW()
2. REPLY / FOLLOW_UP — выполнить все
3. FIRST_CONTACT — проверить TelegramRateLimiter:
   - Разрешено → выполнить ОДНУ, остальные ждут
   - Интервал не прошёл → отодвинуть execute_at
   - Дневной лимит → отодвинуть на завтра
4. При ошибке: attempts++, retry через 5 мин (макс 3 попытки)

### Типы задач

| Тип | Когда создаётся | Payload | Что делает |
|---|---|---|---|
| FIRST_CONTACT | Kafka → MessageTaskConsumer | MessageProcessingTask JSON | FirstContactService.initiateContact() |
| REPLY | Входящее сообщение вне рабочих часов | {conversationId} | ScheduledFollowUpService.sendFollowUpForConversation() |
| FOLLOW_UP | AI-классификатор: "напиши позже" | {conversationId} | ScheduledFollowUpService.sendFollowUpForConversation() |

---

## 13. Admin API

### Персоны: `/internal/admin/personas`

| Метод | Endpoint | Описание |
|---|---|---|
| GET | `/` | Список всех персон |
| GET | `/{ref}` | Персона по ref (с промптами) |
| POST | `/` | Создать новую персону |
| PUT | `/{ref}` | Обновить промпт/шаблон (partial update + evict кэша) |
| DELETE | `/{ref}` | Деактивировать (soft delete + evict) |
| POST | `/cache/evict` | Сбросить весь кэш персон |

### Диалоги: `/internal/admin/conversations`

| Метод | Endpoint | Описание |
|---|---|---|
| GET | `/` | Список диалогов (имена/ID замаскированы) |
| GET | `/{id}` | Диалог с полной историей сообщений |
| GET | `/status/{status}` | Фильтр по статусу |

---

## 14. Маскирование (MaskingUtil)

| Метод | Пример | Результат |
|---|---|---|
| maskPhone | +79817110577 | +798***0577 |
| maskContactId | 491865728 | 4918***728 |
| maskName | Viktor Vikulitko | V. V*** |
| maskChatId | 491865728 | ***5728 |
| truncate(text, 80) | длинный текст... | первые 80 символов... |

---

## 15. Механизм @Tool — ConversationTools

### Архитектура

AI-агент (LangChain4j) может вызывать инструменты (@Tool) во время генерации ответа. Инструменты определены в классе `ConversationTools`, а контекст текущего диалога передаётся через `ThreadLocal<ConversationContext>`.

### ConversationContext (ThreadLocal)

```java
public record ConversationContext(
    Conversation conversation,
    Persona persona
)
```

**Почему ThreadLocal:** LangChain4j вызывает @Tool-методы синхронно в том же потоке, что и `agent.chat()`. ThreadLocal гарантирует, что каждый вызов agent.chat() работает со своим контекстом диалога.

**Жизненный цикл:**
1. Перед `agent.chat()` — `ConversationTools.setContext(conversation, persona)`
2. AI вызывает @Tool → метод читает контекст из ThreadLocal
3. После `agent.chat()` — `ConversationTools.clearContext()` (в finally-блоке)

### Доступные инструменты

#### `sendTestTask`

- **Что делает:** отправляет кандидату ссылку на тестовое задание из `persona.testTaskUrl`
- **Действия:** `TelegramClientService.sendMessageByChatId(contactId, testTaskUrl)` + `conversationService.updateStatus(conversation, TEST_SENT)`
- **Когда AI вызывает:** когда кандидат проявил интерес и готов к следующему этапу (определяется инструкциями в system prompt)
- **Возвращает:** строку-подтверждение для AI (например "Тестовое задание отправлено")

#### `notifyTeam`

- **Что делает:** отправляет уведомление в Telegram контакту из `persona.notificationContact`
- **Действия:** `TelegramClientService.sendByUsername(notificationContact, message)` или `sendMessageByChatId` (если chatId)
- **Когда AI вызывает:** когда кандидат выполнил задание, проявил высокий интерес, или нужно внимание рекрутера
- **Параметр:** текст уведомления (AI формулирует сам)

#### `closeConversation`

- **Что делает:** завершает диалог
- **Действия:** `conversationService.updateStatus(conversation, COMPLETED)`
- **Когда AI вызывает:** когда кандидат отказался, диалог достиг цели, или дальнейшее общение нецелесообразно

---

## 16. Тестовое задание — таймаут и напоминание

### Флоу

После отправки тестового задания (`sendTestTask`) статус диалога переходит в `TEST_SENT`. Система отслеживает таймаут:

1. **TEST_SENT** — тестовое задание отправлено, ждём выполнения
2. **Через N дней** (`TEST_TASK_TIMEOUT_DAYS`, по умолчанию 3) — если кандидат не ответил, AI генерирует напоминание
3. **Ещё через 1 день** — если снова нет ответа, статус переходит в `TIMED_OUT`

### Реализация

`DeferredTaskExecutor` при обработке задач типа `TEST_TASK_REMINDER`:
1. Проверяет, что conversation всё ещё в статусе `TEST_SENT`
2. Генерирует AI-напоминание через `agent.chat()` с контекстом "кандидат не ответил на тестовое задание"
3. Отправляет напоминание в Telegram
4. Планирует финальный таймаут через 1 день (задача типа `TEST_TASK_TIMEOUT`)

`DeferredTaskExecutor` при обработке `TEST_TASK_TIMEOUT`:
1. Если статус всё ещё `TEST_SENT` (кандидат так и не ответил) → `TIMED_OUT`
2. Если статус изменился (кандидат ответил) → задача отменяется

### Статусы диалога (полный список)

| Статус | Описание |
|---|---|
| `INITIATED` | Диалог создан, первое сообщение ещё не отправлено |
| `ACTIVE` | Первое сообщение отправлено, диалог ведётся |
| `TEST_SENT` | Тестовое задание отправлено, ожидание выполнения |
| `COMPLETED` | Диалог завершён (AI вызвал closeConversation) |
| `FAILED` | Ошибка отправки в Telegram |
| `TIMED_OUT` | Таймаут ожидания тестового задания |
| `PRIVACY_BLOCKED` | Кандидат заблокировал приватность |

---

## 17. Уведомления команды (notifyTeam)

### Флоу уведомлений

1. AI в процессе диалога решает, что нужно уведомить команду (например, кандидат готов к собеседованию)
2. AI вызывает `notifyTeam(message)` — @Tool метод
3. `ConversationTools.notifyTeam()` читает `persona.notificationContact` из ThreadLocal-контекста
4. Определяет тип контакта:
   - Начинается с `@` → `TelegramClientService.sendByUsername(contact, message)`
   - Числовой → `TelegramClientService.sendMessageByChatId(Long.parseLong(contact), message)`
5. Сообщение содержит информацию о кандидате и контексте (AI формулирует самостоятельно)

### Пример уведомления

```
Кандидат Viktor V. (candidate_java) выполнил тестовое задание и готов к техническому собеседованию.
Диалог #42, контакт: @vik***ko
```
