# AI Communication Service — Полная схема со всеми ветвлениями

## 1. Входящая задача из Kafka (первый контакт)

```
┌─────────────────────────────────────────────────────────────────┐
│                    Kafka topic: ai.processing.filtered          │
│              MessageProcessingTask: ref, sourceId, aiResult     │
└──────────────────────────────┬──────────────────────────────────┘
                               │
                               ▼
                  ┌────────────────────────┐
                  │  MessageTaskConsumer   │
                  │  @KafkaListener        │
                  └────────────┬───────────┘
                               │
                    ┌──────────▼──────────┐
                    │ isAlreadyProcessed? │
                    └──────────┬──────────┘
                          ╱         ╲
                        YES          NO
                        │            │
                   ack+return    markProcessed
                                 ack сразу
                                     │
                                     ▼
                          ┌─────────────────────┐
                          │ deferNow(task)       │
                          │ → deferred_tasks DB  │
                          │ execute_at = NOW()   │
                          └─────────┬───────────┘
                                    │
         ╔══════════════════════════▼══════════════════════════╗
         ║           deferred_tasks (PostgreSQL)               ║
         ║  Переживает рестарт Heroku, доступна всем dyno     ║
         ╚══════════════════════════╤══════════════════════════╝
                                    │
                                    ▼
                  ┌──────────────────────────────┐
                  │  DeferredTaskExecutor         │
                  │  @Scheduled(каждые 60с)       │
                  │  SELECT FOR UPDATE SKIP LOCKED│
                  └──────────────┬───────────────┘
                                 │
                      ┌──────────▼──────────┐
                      │  isWorkingHours?     │
                      │  (09:00-18:35 МСК)   │
                      └──────────┬──────────┘
                            ╱         ╲
                          NO           YES
                          │            │
                       return     ┌────▼─────┐
                     (ждём утра)  │ Тип?      │
                                  └────┬─────┘
                              ╱        │        ╲
                    FIRST_CONTACT    REPLY    FOLLOW_UP
                         │            │          │
                         ▼            ▼          ▼
              ┌──────────────┐   executeConversationTask
              │ RateLimiter  │   (без лимита)
              └──────┬───────┘
                ╱    │     ╲
           waitSec  waitSec  waitSec
            < 0      > 0      = 0
             │        │        │
         ┌───▼──┐  ┌──▼───┐  ┌▼──────────────────┐
         │завтра│  │сдвиг │  │ FirstContactService│
         │      │  │execute│  │ initiateContact()  │
         │      │  │_at    │  └────────┬───────────┘
         └──────┘  └───────┘           │
          (лимит    (интервал           ▼
           25/день)  не прошёл)   (см. схему 2)
```

## 2. FirstContactService — инициация диалога

```
                  ┌──────────────────────────┐
                  │  FirstContactService     │
                  │  initiateContact(task)   │
                  └────────────┬─────────────┘
                               │
                  ┌────────────▼─────────────┐
                  │ PersonaService.getByRef() │
                  │ (кэш @Cacheable)          │
                  └────────────┬─────────────┘
                          ╱         ╲
                      not found     found
                         │            │
                    Exception    ┌────▼──────────────────┐
                                 │ extractField(aiResult) │
                                 │ contactId, fullName,   │
                                 │ channelType, reasonFit │
                                 └────────┬──────────────┘
                                          │
                              ┌───────────▼──────────────┐
                              │ createConversation()      │
                              │ status = INITIATED        │
                              │ candidateContext = aiResult│
                              └───────────┬──────────────┘
                                          │
                              ┌───────────▼──────────────┐
                              │ renderTemplate()          │
                              │ {{name}} → fullName       │
                              │ {{reasonFit}} → reason    │
                              │ {{любое поле}} → aiResult │
                              └───────────┬──────────────┘
                                          │
                              ┌───────────▼──────────────┐
                              │ enrichedSystemPrompt =    │
                              │ persona.systemPrompt      │
                              │ + candidateContext        │
                              └───────────┬──────────────┘
                                          │
                              ┌───────────▼──────────────┐
                              │ addMessage(USER, prompt)  │
                              │ (для ChatMemory)          │
                              └───────────┬──────────────┘
                                          │
                              ┌───────────▼──────────────┐
                              │ agent.chat(              │
                              │   conversationId,         │
                              │   enrichedSystemPrompt,   │
                              │   firstMessagePrompt      │
                              │ )                         │
                              │ → OpenAI gpt-4o           │
                              └───────────┬──────────────┘
                                          │
                              ┌───────────▼──────────────┐
                              │ addMessage(ASSISTANT,     │
                              │   aiResponse)             │
                              └───────────┬──────────────┘
                                          │
                              ┌───────────▼──────────────┐
                              │ TelegramClientService    │
                              │ sendMessageByChatId()    │
                              └───────────┬──────────────┘
                                          │
                                  (см. схему 4)
                                          │
                                     ╱         ╲
                                  успех       ошибка
                                    │            │
                            status=ACTIVE   status=FAILED
```

## 3. Входящее сообщение от кандидата

```
┌─────────────────────────────────────────────────────────────┐
│              Telegram — кандидат отправил сообщение          │
│    (текст / фото / стикер / голосовое / документ / ...)     │
└──────────────────────────────┬──────────────────────────────┘
                               │
                               ▼
                  ┌────────────────────────────┐
                  │  TDLib thread (callback)    │
                  │  TelegramUpdateHandler      │
                  │  onUpdateNewMessage()       │
                  └────────────┬───────────────┘
                               │
                    ┌──────────▼──────────┐
                    │   isOutgoing?        │
                    └──────────┬──────────┘
                          ╱         ╲
                        YES          NO
                         │            │
                      return     ┌────▼────────────────────┐
                                 │ extractContentDescription│
                                 └────────┬────────────────┘
                                     ╱         ╲
                                  null        ContentDescription
                                   │              │
                             return (service     ┌▼───────────────┐
                             messages,           │ type + text     │
                             polls и пр.)        │ "text" → текст  │
                                                 │ "photo" → [Фото]│
                                                 │ "sticker" → [😁]│
                                                 │ "emoji" → 👍    │
                                                 │ и т.д.          │
                                                 └──────┬─────────┘
                                                        │
                                           ┌────────────▼──────────┐
                                           │ markAsRead(chatId)    │
                                           │ → синие галочки       │
                                           └────────────┬──────────┘
                                                        │
                                           ┌────────────▼──────────┐
                                           │ continuationService   │
                                           │ .handleIncomingReply()│
                                           │ @Async (не блокирует │
                                           │  TDLib thread)        │
                                           └────────────┬──────────┘
                                                        │
                                                        ▼
                                                  (см. схему 3.1)
```

## 3.1 ConversationContinuationService — обработка ответа

```
                  ┌────────────────────────────────┐
                  │ handleIncomingReply(senderId,   │
                  │                    messageText) │
                  └──────────────┬─────────────────┘
                                 │
                      ┌──────────▼──────────────┐
                      │ findActiveByContact      │
                      │ (TELEGRAM, senderId)     │
                      └──────────┬──────────────┘
                            ╱         ╲
                        empty         found
                          │              │
                   return (не наш   ┌────▼───────────────┐
                   диалог: группа,  │ getByRef(ref)      │
                   канал, или       │ → Persona (кэш)    │
                   conversation     └────┬───────────────┘
                   уже COMPLETED)        │
                                    ╱         ╲
                                empty         found
                                  │              │
                            log error      ┌─────▼──────────────────┐
                                           │ addMessage(USER, text)  │
                                           │ (сохраняем СРАЗУ)       │
                                           └─────┬──────────────────┘
                                                  │
                          ┌───────────────────────▼──────────────────────┐
                          │ ScheduledFollowUpService                      │
                          │ .analyzeAndScheduleIfNeeded(messageText)     │
                          │                                               │
                          │ AI-классификатор: "просит написать позже?"    │
                          │ ChatLanguageModel.chat(classifierPrompt)      │
                          └───────────────────────┬──────────────────────┘
                                             ╱         ╲
                                          0 мин       > 0 мин
                                           │             │
                                      (ничего)    ┌──────▼──────────┐
                                                   │ deferWithDelay   │
                                                   │ FOLLOW_UP        │
                                                   │ в deferred_tasks │
                                                   └─────────────────┘
                                           │
                          ┌────────────────▼────────────────┐
                          │ DeferredMessageService          │
                          │ .executeOrDefer(REPLY, ...)     │
                          └────────────────┬───────────────┘
                                      ╱         ╲
                              рабочее            нерабочее
                              время               время
                                │                    │
                           execute         ┌─────────▼──────────┐
                           сразу           │ defer(REPLY)        │
                                │          │ → deferred_tasks    │
                                │          │ execute_at =        │
                                │          │ nextWorkStart+рандом│
                                │          └────────────────────┘
                                │          (утром DeferredTask-
                                │           Executor подберёт)
                                ▼
                  ┌──────────────────────────────┐
                  │ generateAndSendReply()        │
                  └──────────────┬───────────────┘
                                 │
                  ┌──────────────▼───────────────┐
                  │ enrichedSystemPrompt =        │
                  │ persona.systemPrompt          │
                  │ + conversation.candidateContext│
                  └──────────────┬───────────────┘
                                 │
                  ┌──────────────▼───────────────┐
                  │ agent.chat(                   │
                  │   conversationId,              │
                  │   enrichedSystemPrompt,        │
                  │   messageText                  │
                  │ )                              │
                  │                                │
                  │ ChatMemory загружает           │
                  │ последние 20 сообщений из БД   │
                  │ + system prompt с контекстом   │
                  │ → OpenAI gpt-4o                │
                  └──────────────┬───────────────┘
                                 │
                  ┌──────────────▼───────────────┐
                  │ addMessage(ASSISTANT, reply)  │
                  └──────────────┬───────────────┘
                                 │
                  ┌──────────────▼───────────────┐
                  │ TelegramClientService         │
                  │ sendMessageByChatId(reply)    │
                  └──────────────┬───────────────┘
                                 │
                                 ▼
                           (см. схему 4)
```

## 4. Отправка сообщения в Telegram (human simulation)

```
                  ┌──────────────────────────────┐
                  │ sendMessageByChatId(chatId,   │
                  │                    text)      │
                  └──────────────┬───────────────┘
                                 │
                  ┌──────────────▼───────────────┐
                  │ Read Delay                    │
                  │ random(3000-15000)мс          │
                  │                               │
                  │ Что видит кандидат: НИЧЕГО     │
                  │ (HR "занят", читает, думает)   │
                  └──────────────┬───────────────┘
                                 │
                  ┌──────────────▼───────────────┐
                  │ simulateTypingWithRefresh()   │
                  │                               │
                  │ SendChatAction(Typing)         │
                  │ каждые 4с (Telegram сбрасывает │
                  │ индикатор через ~5с)           │
                  │                               │
                  │ Длительность:                  │
                  │ clamp(120мс×chars + jitter,    │
                  │       3с, 55с)                 │
                  │                               │
                  │ Что видит кандидат:            │
                  │ "Анна печатает..."              │
                  └──────────────┬───────────────┘
                                 │
                  ┌──────────────▼───────────────┐
                  │ sendWithRetry(chatId, text, 0)│
                  └──────────────┬───────────────┘
                                 │
                            ╱         ╲
                         успех       ошибка
                           │            │
                     ┌─────▼─────┐   ┌──▼───────────────┐
                     │ Message    │   │ isRetryable?      │
                     │ sent ✓     │   │ 429/500/420 → да  │
                     └───────────┘   │ 400/403/404 → нет │
                                     └──────┬───────────┘
                                        ╱         ╲
                                     retryable  permanent
                                       │           │
                                ┌──────▼──────┐  Exception
                                │ attempt < 3? │  (FAILED)
                                └──────┬──────┘
                                  ╱         ╲
                                YES          NO
                                 │            │
                          delay(2^n с)    Exception
                          retry            (FAILED)
```

## 5. Scheduled Follow-Up ("напиши позже")

```
                  ┌──────────────────────────────────────────┐
                  │ Кандидат: "могу говорить через 5 минут"   │
                  └──────────────────────┬───────────────────┘
                                         │
                  ┌──────────────────────▼───────────────────┐
                  │ ScheduledFollowUpService                  │
                  │ .analyzeAndScheduleIfNeeded()             │
                  │                                           │
                  │ Отдельный AI-вызов (не через агента):     │
                  │ "Просит написать позже? → число минут"    │
                  │ ChatLanguageModel.chat(classifierPrompt)  │
                  └──────────────────────┬───────────────────┘
                                    ╱         ╲
                                 0 мин       > 0 мин
                                  │              │
                             (ничего)     ┌──────▼──────────────────┐
                                          │ deferWithDelay(          │
                                          │   FOLLOW_UP,             │
                                          │   {conversationId},      │
                                          │   delayMinutes            │
                                          │ )                        │
                                          │ → deferred_tasks DB      │
                                          │ execute_at = now + N мин │
                                          └──────────┬──────────────┘
                                                     │
                                             ... N минут ...
                                                     │
                  ┌──────────────────────────────────▼──────────────┐
                  │ DeferredTaskExecutor (poll)                      │
                  │ → executeConversationTask(FOLLOW_UP)            │
                  │ → sendFollowUpForConversation(conversationId)   │
                  └──────────────────────────────────┬──────────────┘
                                                     │
                  ┌──────────────────────────────────▼──────────────┐
                  │ enrichedSystemPrompt + FOLLOW_UP_PROMPT          │
                  │ "Ты договорилась написать позже. Время пришло." │
                  │ → agent.chat() → AI генерирует напоминание      │
                  │ → addMessage → sendMessageByChatId              │
                  └────────────────────────────────────────────────┘
```

## 6. Онлайн-статус (WorkScheduleService)

```
               ┌──────────────────────────────────┐
               │ @Scheduled(каждые 60с)            │
               │ checkAndToggleOnlineStatus()      │
               └──────────────────┬───────────────┘
                                  │
                       ┌──────────▼──────────┐
                       │  isWorkingHours?     │
                       └──────────┬──────────┘
                             ╱         ╲
                           YES          NO
                            │            │
              SetOption("online", true)  SetOption("online", false)
                            │            │
              Кандидат видит:           Кандидат видит:
              "в сети"                  "был(а) недавно"
```

## 7. Полный жизненный цикл conversation

```
                           ┌───────────┐
                           │ INITIATED │
                           └─────┬─────┘
                                 │ FirstContactService
                                 │ отправил первое сообщение
                                 │
                            ╱         ╲
                      Telegram       Telegram
                       OK            ERROR
                        │               │
                  ┌─────▼─────┐   ┌────▼────┐
                  │  ACTIVE   │   │ FAILED  │
                  └─────┬─────┘   └─────────┘
                        │
            ┌───────────┼───────────┐
            │           │           │
       кандидат    кандидат     кандидат
       отвечает    молчит     отправил
            │           │      стикер/фото
            │           │           │
         AI отвечает   (TODO:    AI реагирует
         в диалоге     timeout   естественно
            │          → follow  "[Стикер: 😁]"
            │           up)          │
            │                        │
            ▼                        ▼
      ┌──────────┐            ┌──────────┐
      │ Диалог   │            │ Диалог   │
      │продолж-ся│            │продолж-ся│
      └────┬─────┘            └──────────┘
           │
      ╱    │    ╲
  "напиши  │   (TODO:
  позже"   │   goalReached
     │     │   → email HR
  FOLLOW_UP│   → COMPLETED)
  через    │
  N мин    │
           │
     ┌─────▼──────┐
     │   ACTIVE    │
     │ (продолжает)│
     └─────────────┘
```

## 8. Обзор всех хранилищ данных

```
┌─────────────────────────────────────────────────────────────────┐
│                        PostgreSQL                               │
│                                                                 │
│  ┌─────────────────────┐  ┌──────────────────────────────┐     │
│  │ processed_messages   │  │ conversations                │     │
│  │ source_id (UNIQUE)   │  │ id, source_id, ref,          │     │
│  │ → идемпотентность    │  │ full_name, contact_id,       │     │
│  └─────────────────────┘  │ candidate_context,            │     │
│                            │ channel_type, status          │     │
│  ┌─────────────────────┐  │ (INITIATED/ACTIVE/COMPLETED/  │     │
│  │ personas             │  │  FAILED/PRIVACY_BLOCKED)      │     │
│  │ ref, label,          │  └──────────────┬───────────────┘     │
│  │ system_prompt,       │                 │ 1:N                 │
│  │ first_message_       │  ┌──────────────▼───────────────┐     │
│  │ template             │  │ conversation_messages         │     │
│  │ → промпты из БД,     │  │ conversation_id, role,        │     │
│  │   @Cacheable          │  │ content, created_at           │     │
│  └─────────────────────┘  │ → ChatMemory для LangChain4j  │     │
│                            └──────────────────────────────┘     │
│  ┌─────────────────────────────────────────────────────┐       │
│  │ deferred_tasks                                       │       │
│  │ task_type (FIRST_CONTACT/REPLY/FOLLOW_UP)           │       │
│  │ payload (JSON), execute_at, status, attempts        │       │
│  │ → очередь задач, переживает рестарт                  │       │
│  │ → SELECT FOR UPDATE SKIP LOCKED (multi-instance)    │       │
│  └─────────────────────────────────────────────────────┘       │
│                                                                 │
├─────────────────────────────────────────────────────────────────┤
│                     Внешние системы                             │
│                                                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐       │
│  │ Kafka    │  │ Telegram │  │ OpenAI   │  │ TDLib    │       │
│  │ (вход)   │  │ (выход)  │  │ (AI)     │  │ (сессия) │       │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘       │
│                                                                 │
│  ┌──────────────────────────────┐                               │
│  │ Spring Cache (in-memory)     │                               │
│  │ personas → @Cacheable        │                               │
│  └──────────────────────────────┘                               │
└─────────────────────────────────────────────────────────────────┘
```
