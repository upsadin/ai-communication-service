# AI Communication Service

Сервис проактивной AI-коммуникации с кандидатами через Telegram. Получает задания из Kafka, генерирует персонализированные сообщения через OpenAI (gpt-4o) и ведёт диалог от имени реального Telegram-аккаунта (MTProto, не Bot API).

## Быстрый старт

### Требования

- Java 21
- Docker + Docker Compose
- OpenAI API ключ
- Telegram API credentials (https://my.telegram.org)

### 1. Запуск инфраструктуры

```bash
docker compose up -d
```

Поднимает PostgreSQL (порт 5433) и Kafka KRaft (порт 29092).

### 2. Настройка секретов

Создайте файл `src/main/resources/application-local.yml` (уже в .gitignore):

```yaml
telegram:
  api-id: <ваш_api_id>
  api-hash: <ваш_api_hash>
  phone: <номер_с_кодом_страны>

langchain4j:
  open-ai:
    chat-model:
      api-key: <ваш_openai_ключ>
```

### 3. Запуск приложения

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### 4. Авторизация Telegram

При первом запуске TDLib запросит OTP-код. Код придёт в Telegram.

```bash
# Отправить OTP
curl -X POST http://localhost:8080/internal/telegram/otp \
  -H "Content-Type: application/json" \
  -d '{"code":"12345"}'

# Проверить статус
curl http://localhost:8080/internal/telegram/status
# → {"status":"READY"}
```

Сессия сохраняется в `tdlib-session/` — при перезапуске OTP не нужен.

### 5. Тестовая отправка через Kafka

```bash
docker exec aicomm-kafka sh -c "echo '{
  \"ref\":\"candidate_java\",
  \"sourceId\":\"test-001\",
  \"aiResult\":{
    \"matches\":true,
    \"confidence\":0.9,
    \"reason\":\"5 лет опыта в Java и Spring Boot\",
    \"full_name\":\"Viktor\",
    \"contacts\":{
      \"telegram\":\"vikulitko\",
      \"email\":null,
      \"phone\":null,
      \"linkedin\":null
    }
  }
}' | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic ai.processing.filtered"
```

---

## Переменные окружения

Все параметры конфигурируются через переменные окружения. Дефолтные значения рассчитаны на локальную разработку.

### Секреты (обязательные на проде)

| Переменная | Описание | Пример |
|---|---|---|
| `TELEGRAM_API_ID` | Telegram API ID | `20405054` |
| `TELEGRAM_API_HASH` | Telegram API hash | `57da0dd...` |
| `TELEGRAM_PHONE` | Номер телефона | `+79817110577` |
| `OPENAI_API_KEY` | OpenAI API ключ | `sk-proj-...` |
| `DB_URL` | PostgreSQL URL | `jdbc:postgresql://host:5432/aicomm` |
| `DB_USERNAME` | БД логин | `aicomm` |
| `DB_PASSWORD` | БД пароль | `secret` |

### Kafka

| Переменная | Дефолт | Описание |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092` | Kafka broker |
| `KAFKA_TOPIC_MESSAGE_TASKS` | `ai.processing.filtered` | Входной топик |
| `KAFKA_CONSUMER_GROUP` | `aicomm-consumer-group` | Consumer group |

### OpenAI

| Переменная | Дефолт | Описание |
|---|---|---|
| `OPENAI_MODEL` | `gpt-4o` | Модель |
| `OPENAI_TEMPERATURE` | `0.7` | Температура (0 = точный, 1 = свободный) |
| `OPENAI_MAX_TOKENS` | `500` | Макс длина ответа |

### Рабочее время и поведение

| Переменная | Дефолт | Описание |
|---|---|---|
| `WORK_START` | `09:00` | Начало рабочего дня |
| `WORK_END` | `18:35` | Конец рабочего дня |
| `WORK_TIMEZONE` | `Europe/Moscow` | Часовой пояс |
| `WORK_DAYS` | `MONDAY,...,FRIDAY` | Рабочие дни |
| `READ_DELAY_MIN_MS` | `3000` | Мин пауза перед "печатает" |
| `READ_DELAY_MAX_MS` | `15000` | Макс пауза перед "печатает" |
| `TYPING_MS_PER_CHAR` | `120` | Мс на символ при печатании |
| `TYPING_MIN_MS` | `3000` | Мин время печатания |
| `TYPING_MAX_MS` | `55000` | Макс время печатания |

### Rate Limiting

| Переменная | Дефолт | Описание |
|---|---|---|
| `RATE_LIMIT_FIRST_CONTACT_SEC` | `120` | Мин интервал между первыми контактами |
| `RATE_LIMIT_MAX_FIRST_CONTACTS_DAY` | `25` | Макс новых контактов в день |

### AI Agent

| Переменная | Дефолт | Описание |
|---|---|---|
| `AGENT_MEMORY_MAX_MESSAGES` | `20` | Скользящее окно истории (сообщений) |
| `TEST_TASK_TIMEOUT_DAYS` | `3` | Дней ожидания тестового задания до напоминания |

---

## Персоны и field_mapping

Персона определяет поведение AI-агента для конкретного `ref`. Администратор настраивает персону через Admin API или напрямую в БД.

### Структура персоны

| Поле | Описание |
|---|---|
| `ref` | Уникальный идентификатор (совпадает с ref в Kafka) |
| `label` | Внутренняя метка для логов и админки |
| `system_prompt` | Промпт: характер, стиль, правила общения AI |
| `first_message_template` | Шаблон первого сообщения с `{{плейсхолдерами}}` |
| `field_mapping` | JSON: откуда в aiResult брать имя, контакт, причину |
| `test_task_url` | Ссылка на тестовое задание. AI отправляет её кандидату через `sendTestTask` tool |
| `notification_contact` | Telegram username (напр. `@bubligoom`) или chatId для уведомлений команды |
| `active` | Активна ли персона |

#### test_task_url

Ссылка на тестовое задание, которую AI-агент отправляет кандидату через инструмент `sendTestTask`. Меняется через Admin API без редеплоя — можно обновить ссылку для конкретной персоны в любой момент.

#### notification_contact

Telegram username (например `@bubligoom`) или числовой chatId, куда система отправляет уведомления команде. Используется инструментом `notifyTeam` — AI вызывает его, когда кандидат готов к следующему этапу или требуется внимание рекрутера. Также меняется через Admin API.

#### Инструменты AI-агента (@Tool)

Системный промпт персоны должен содержать инструкции о стадиях диалога и доступных инструментах:

- **`sendTestTask`** — отправляет кандидату ссылку на тестовое задание из `test_task_url` персоны. После вызова статус диалога меняется на `TEST_SENT`
- **`notifyTeam`** — отправляет уведомление в Telegram контакту из `notification_contact` персоны (например, когда кандидат выполнил задание или проявил высокий интерес)
- **`closeConversation`** — завершает диалог, статус переходит в `COMPLETED`

AI сам решает когда вызвать инструмент, основываясь на контексте диалога и инструкциях в system prompt.

### field_mapping — подробно

`field_mapping` — JSON-объект, который говорит сервису **где в aiResult искать нужные данные**. Это необходимо, потому что структура aiResult зависит от `ref` — для каждого ref upstream-сервис может возвращать разные поля.

#### Как работает

Сервису нужны три ключевых данных из aiResult:
1. **Имя кандидата** — для шаблона первого сообщения и записи в БД
2. **Контакт в Telegram** — чтобы знать кому отправить сообщение
3. **Причина/контекст** — для шаблона первого сообщения

`field_mapping` указывает путь к каждому из этих полей:

```json
{
  "nameField": "full_name",
  "contactField": "contacts.telegram",
  "reasonField": "reason"
}
```

#### Поддержка вложенных путей

Используется точечная нотация для вложенных объектов:

- `"full_name"` → `aiResult.full_name`
- `"contacts.telegram"` → `aiResult.contacts.telegram`
- `"profile.experience.years"` → `aiResult.profile.experience.years`

#### Пример: candidate_java

Upstream-сервис для `ref=candidate_java` использует такую JSON Schema:

```json
{
  "matches": true,
  "confidence": 0.9,
  "reason": "5 лет опыта в Java и Spring Boot",
  "full_name": "Viktor Vikulitko",
  "contacts": {
    "email": "test@mail.com",
    "phone": "+79999999999",
    "linkedin": null,
    "telegram": "vikulitko"
  }
}
```

Соответствующий `field_mapping`:

```json
{
  "nameField": "full_name",
  "contactField": "contacts.telegram",
  "reasonField": "reason"
}
```

Результат:
- `nameField` → `"Viktor Vikulitko"` — записывается в `conversations.full_name`
- `contactField` → `"vikulitko"` — используется для отправки в Telegram
- `reasonField` → `"5 лет опыта в Java и Spring Boot"` — подставляется в `{{reason}}` шаблона

#### Пример: гипотетический client_sales

Для другого ref (продажи) структура aiResult может быть совсем другой:

```json
{
  "company_name": "ООО Рога и Копыта",
  "contact_person": {
    "name": "Иван Петров",
    "telegram": "ivan_petrov"
  },
  "why_relevant": "Компания ищет IT-аутсорсинг"
}
```

`field_mapping` для этого ref:

```json
{
  "nameField": "contact_person.name",
  "contactField": "contact_person.telegram",
  "reasonField": "why_relevant"
}
```

#### Шаблон первого сообщения

`first_message_template` использует `{{плейсхолдеры}}`, которые заполняются из aiResult:

- `{{name}}` — значение по пути из `nameField`
- `{{reason}}` — значение по пути из `reasonField`
- `{{любое_поле}}` — напрямую из верхнего уровня aiResult (например `{{confidence}}`)

Пример шаблона:

```
Напиши первое сообщение кандидату. Вот что ты о нём знаешь:
- Имя: {{name}} (если имя латиницей — напиши его кириллицей)
- Что зацепило: {{reason}}

Поздоровайся, коротко упомяни что заинтересовало в профиле и спроси, актуален ли сейчас поиск.
```

#### Добавление нового ref

1. Определите JSON Schema в upstream-сервисе
2. Создайте персону через Admin API:

```bash
curl -X POST http://localhost:8080/internal/admin/personas \
  -H "Content-Type: application/json" \
  -d '{
    "ref": "client_sales",
    "label": "Sales Manager",
    "systemPrompt": "Ты — менеджер по продажам. У тебя есть инструменты: sendTestTask (отправить тестовое), notifyTeam (уведомить команду), closeConversation (завершить диалог)...",
    "firstMessageTemplate": "Напиши первое сообщение. Компания: {{name}}. Почему подходят: {{reason}}",
    "fieldMapping": "{\"nameField\":\"company_name\",\"contactField\":\"contact_person.telegram\",\"reasonField\":\"why_relevant\"}",
    "testTaskUrl": "https://docs.google.com/forms/d/e/xxx/viewform",
    "notificationContact": "@bubligoom"
  }'
```

3. Код не нужно менять — всё настраивается через БД.

---

## Admin API

### Персоны: `/internal/admin/personas`

```bash
# Список всех персон
GET /internal/admin/personas

# Персона по ref
GET /internal/admin/personas/{ref}

# Создать
POST /internal/admin/personas
Body: {"ref", "label", "systemPrompt", "firstMessageTemplate", "fieldMapping", "testTaskUrl", "notificationContact"}

# Обновить (partial update)
PUT /internal/admin/personas/{ref}
Body: {"label", "systemPrompt", "firstMessageTemplate", "fieldMapping", "testTaskUrl", "notificationContact", "active"}

# Деактивировать
DELETE /internal/admin/personas/{ref}

# Сбросить кэш
POST /internal/admin/personas/cache/evict
```

### Диалоги: `/internal/admin/conversations`

```bash
# Список всех (имена/ID замаскированы)
GET /internal/admin/conversations

# Диалог с полной историей
GET /internal/admin/conversations/{id}

# Фильтр по статусу
GET /internal/admin/conversations/status/ACTIVE
```

### Telegram: `/internal/telegram`

```bash
# Статус авторизации
GET /internal/telegram/status

# Отправить OTP
POST /internal/telegram/otp
Body: {"code":"12345"}

# Тестовая отправка
POST /internal/telegram/test-send
Body: {"username":"vikulitko","text":"Hello!"}
```

---

## Тесты

```bash
# Запуск всех тестов
mvn test

# Текущий результат: 65 тестов, все зелёные
```

---

## Heroku Deployment

### Переменные окружения

На Heroku все секреты задаются через Config Vars:

```bash
heroku config:set TELEGRAM_API_ID=20405054
heroku config:set TELEGRAM_API_HASH=57da0dd...
heroku config:set TELEGRAM_PHONE=+79817110577
heroku config:set OPENAI_API_KEY=sk-proj-...
heroku config:set DB_URL=jdbc:postgresql://host:5432/db
heroku config:set DB_USERNAME=user
heroku config:set DB_PASSWORD=pass
heroku config:set KAFKA_BOOTSTRAP_SERVERS=broker:9092
```

Изменение config var → автоматический рестарт dyno → значение подхватывается. Редеплой не нужен.

### Особенности

- **TDLib-сессия**: Heroku filesystem эфемерный — сессия теряется при рестарте. Нужна S3-синхронизация (TODO)
- **Несколько dyno**: `deferred_tasks` использует `SELECT FOR UPDATE SKIP LOCKED` — безопасно для нескольких инстансов
- **Отложенные задачи**: хранятся в PostgreSQL, переживают рестарт
