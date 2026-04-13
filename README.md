# AI Communication Service

Сервис проактивной AI-коммуникации с кандидатами через Telegram. Получает задания из Kafka, генерирует персонализированные сообщения через OpenAI (gpt-4o) и ведёт диалог от имени реального Telegram-аккаунта (MTProto, не Bot API).

**Swagger UI:** https://project-007-3-ai-communication-10a1ebc2993e.herokuapp.com/swagger-ui.html

---

## Содержание

1. [Быстрый старт (локально)](#быстрый-старт)
2. [Деплой на Heroku](#heroku-deployment)
3. [Swagger UI — управление через браузер](#swagger-ui)
4. [Авторизация Telegram (OTP)](#авторизация-telegram-otp)
5. [Flow диалога — как работают сообщения](#flow-диалога)
6. [Персоны — как настроить AI-агента](#персоны-и-field_mapping)
7. [Как написать системный промпт](#как-написать-системный-промпт)
8. [Тестирование через Kafka UI](#тестирование-через-kafka-ui)
9. [Admin API](#admin-api)
10. [Переменные окружения](#переменные-окружения)
11. [Тесты](#тесты)

---

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

При первом запуске TDLib запросит OTP-код. См. раздел [Авторизация Telegram (OTP)](#авторизация-telegram-otp).

---

## Heroku Deployment

### Переменные окружения

На Heroku все секреты задаются через Config Vars. Heroku автоматически задаёт `JDBC_DATABASE_URL`, `DB_USERNAME`, `DB_PASSWORD` при подключении Heroku Postgres.

```bash
heroku config:set TELEGRAM_API_ID=20405054 --app <имя-приложения>
heroku config:set TELEGRAM_API_HASH=57da0dd... --app <имя-приложения>
heroku config:set TELEGRAM_PHONE=+79817110577 --app <имя-приложения>
heroku config:set OPENAI_API_KEY=sk-proj-... --app <имя-приложения>
```

Изменение config var → автоматический рестарт dyno → значение подхватывается. Редеплой не нужен.

### Деплой

```bash
heroku container:push web --app <имя-приложения>
heroku container:release web --app <имя-приложения>
```

### Особенности

- **TDLib-сессия**: Heroku filesystem эфемерный — сессия теряется при рестарте. После каждого рестарта нужно заново вводить OTP
- **Несколько dyno**: `deferred_tasks` использует `SELECT FOR UPDATE SKIP LOCKED` — безопасно для нескольких инстансов
- **Отложенные задачи**: хранятся в PostgreSQL, переживают рестарт

---

## Swagger UI

Все эндпоинты доступны через интерактивный Swagger UI с описаниями и примерами запросов.

**URL:** https://project-007-3-ai-communication-10a1ebc2993e.herokuapp.com/swagger-ui.html

Swagger UI разбит на три группы:

| Группа | Описание |
|---|---|
| **Personas** | CRUD-управление персонами: промпты, шаблоны сообщений, тестовые задания, маппинг полей |
| **Conversations** | Просмотр диалогов с кандидатами и истории сообщений (контакты маскируются) |
| **Telegram** | Авторизация (OTP), проверка статуса, тестовая отправка сообщений |

Через Swagger UI можно:
- Ввести OTP-код для авторизации Telegram (вместо терминала)
- Обновить системный промпт или шаблоны сообщений без редеплоя
- Просмотреть текущие диалоги и их историю
- Отправить тестовое сообщение

---

## Авторизация Telegram (OTP)

При каждом старте приложения (или после рестарта на Heroku) нужно пройти авторизацию Telegram. Это одноразовый код, который Telegram присылает на номер телефона аккаунта.

### Шаг 1: Проверьте статус

**Через терминал:**
```bash
curl https://project-007-3-ai-communication-10a1ebc2993e.herokuapp.com/internal/telegram/status
```

**Через Swagger UI:**
Откройте Swagger UI → раздел **Telegram** → `GET /internal/telegram/status` → **Try it out** → **Execute**

Ответы:
- `{"status":"WAITING_CODE"}` — нужно ввести OTP
- `{"status":"READY"}` — уже авторизован, всё работает
- `{"status":"WAITING_PASSWORD"}` — нужен облачный пароль (2FA)

### Шаг 2: Дождитесь кода в Telegram

Telegram пришлёт код в виде сообщения на телефон (или через приложение Telegram). Обычно приходит в течение 10-30 секунд.

### Шаг 3: Введите код

**Через терминал:**
```bash
curl -X POST https://project-007-3-ai-communication-10a1ebc2993e.herokuapp.com/internal/telegram/otp \
  -H "Content-Type: application/json" \
  -d '{"code":"12345"}'
```

**Через Swagger UI:**
Откройте Swagger UI → раздел **Telegram** → `POST /internal/telegram/otp` → **Try it out** → введите код в поле `code` → **Execute**

Замените `12345` на реальный код из Telegram.

### Шаг 4: Проверьте результат

Повторите шаг 1 — статус должен быть `READY`.

### Если пришёл 2FA (облачный пароль)

Если на аккаунте включена двухфакторная аутентификация, после ввода OTP статус будет `WAITING_PASSWORD`. Используйте тот же эндпоинт `/otp`, но передайте облачный пароль вместо кода.

### Частые проблемы

| Проблема | Решение |
|---|---|
| `404 Not Found` при вводе OTP | Приложение ещё не запустилось — подождите 30-60 секунд |
| Код не приходит | Попробуйте снова через минуту — Telegram ограничивает частоту отправки |
| `WAITING_CODE` после ввода OTP | Возможно, неправильный код — введите заново когда придёт новый |
| Сессия сбрасывается | На Heroku файловая система эфемерная — после каждого рестарта dyno нужен новый OTP |

---

## Flow диалога

Диалог с кандидатом состоит из захардкоженных шаблонов (первые два сообщения) и AI-генерации (все последующие).

### Сообщение 1: Знакомство (шаблон, без AI)

**Триггер:** Kafka-сообщение с данными кандидата.

Отправляется `first_message_template` из персоны с подставленным именем (`{{name}}`). AI не участвует — текст рендерится напрямую из шаблона.

Пример:
> Здравствуйте, Роман! Меня зовут Олег, я из компании «LuxTech». Мы ищем Java-разработчика в команду, и Ваш опыт нас очень заинтересовал. Подскажите, актуален ли для Вас сейчас поиск работы?

### Сообщение 2: Вопросы об опыте + тестовое (шаблон, без AI)

**Триггер:** кандидат ответил на первое сообщение.

Отправляется `second_message_template` из персоны. AI не участвует. Шаблон содержит вопросы о стеке/опыте и упоминание тестового задания.

Пример:
> Рад это слышать! Расскажите немного о себе — какой у Вас основной стек технологий, сколько лет в коммерческой разработке и чем занимались на последнем проекте?
>
> Также у нас предусмотрено небольшое техническое задание, чтобы команда могла лучше оценить навыки до встречи. Если Вам это подходит — можете сразу вместе с ответами сказать, готовы ли приступить.

### Сообщение 3+: AI-агент

**Триггер:** кандидат ответил на второе сообщение (и все дальнейшие реплики).

С этого момента подключается AI-агент (OpenAI gpt-4o) с системным промптом персоны. Агент видит всю предыдущую историю (включая шаблонные сообщения) и может использовать инструменты:
- `sendTestTask` — отправить ссылку на тестовое задание
- `notifyTeam` — уведомить рекрутера
- `closeConversation` — завершить диалог

### Почему первые два сообщения захардкожены?

1. **Предсказуемость** — AI-модели склонны игнорировать multi-step инструкции и перескакивать к тестовому заданию сразу
2. **Экономия** — минус 1 вызов OpenAI API на каждый диалог
3. **Скорость** — шаблонное сообщение отправляется мгновенно, без ожидания ответа от OpenAI

---

## Персоны и field_mapping

Персона определяет поведение AI-агента для конкретного `ref`. Администратор настраивает персону через Swagger UI или Admin API.

### Структура персоны

| Поле | Описание |
|---|---|
| `ref` | Уникальный идентификатор (совпадает с ref в Kafka) |
| `label` | Внутренняя метка для логов и админки |
| `system_prompt` | Промпт: характер, стиль, правила общения AI |
| `first_message_template` | Шаблон 1-го сообщения с `{{плейсхолдерами}}`. Отправляется без AI |
| `second_message_template` | Шаблон 2-го сообщения (вопросы об опыте + тестовое). Отправляется без AI |
| `field_mapping` | JSON: откуда в aiResult брать имя, контакт, причину |
| `test_task_url` | Ссылка на тестовое задание. AI отправляет её через `sendTestTask` |
| `notification_contact` | Telegram username (напр. `@bubligoom`) для уведомлений команды |
| `active` | Активна ли персона |

### field_mapping — подробно

`field_mapping` — JSON-объект, который говорит сервису **где в aiResult искать нужные данные**.

#### Доступные ключи

| Ключ | Назначение |
|---|---|
| `nameField` | Путь к имени кандидата |
| `contactField` | Путь к контакту Telegram (username или phone) |
| `contactFallbackField` | Резервный контакт (если `contactField` пустой — берётся этот) |
| `reasonField` | Путь к причине/контексту для первого сообщения |

#### Поддержка вложенных путей

Используется точечная нотация: `"contacts.telegram"` → `aiResult.contacts.telegram`

#### Пример field_mapping

```json
{
  "nameField": "full_name",
  "contactField": "contacts.telegram",
  "contactFallbackField": "contacts.phone",
  "reasonField": "reason"
}
```

### Шаблоны сообщений

#### first_message_template

Готовый текст первого сообщения. Поддерживает `{{плейсхолдеры}}`:
- `{{name}}` — **только имя** (первое слово из full_name, напр. "Павел" из "Павел Иванов")
- `{{reason}}` — значение по пути из `reasonField`
- `{{любое_поле}}` — напрямую из верхнего уровня aiResult

Пример:
```
Здравствуйте, {{name}}! Меня зовут Олег, я из компании «LuxTech». Мы ищем Java-разработчика в команду, и Ваш опыт нас очень заинтересовал. Подскажите, актуален ли для Вас сейчас поиск работы?
```

#### second_message_template

Готовый текст второго сообщения. Отправляется без AI при первом ответе кандидата. Плейсхолдеры не поддерживаются — текст отправляется как есть.

### Добавление нового ref

Через Swagger UI: раздел **Personas** → `POST /internal/admin/personas` → **Try it out** → заполните JSON → **Execute**

Или через терминал:
```bash
curl -X POST https://project-007-3-ai-communication-10a1ebc2993e.herokuapp.com/internal/admin/personas \
  -H "Content-Type: application/json" \
  -d '{
    "ref": "candidate_python",
    "label": "Python HR Олег",
    "systemPrompt": "Ты — Олег, HR-менеджер...",
    "firstMessageTemplate": "Здравствуйте, {{name}}! Меня зовут Олег из LuxTech...",
    "secondMessageTemplate": "Отлично! Расскажите о стеке, опыте...",
    "fieldMapping": "{\"nameField\":\"full_name\",\"contactField\":\"contacts.telegram\"}",
    "testTaskUrl": "https://example.com/test",
    "notificationContact": "@admin"
  }'
```

Код менять не нужно — всё настраивается через API.

---

## Как написать системный промпт

Системный промпт (`system_prompt`) — инструкция для AI о том, **кем он является**, **как общается** и **что делает** в диалоге. AI подключается только с 3-го сообщения — первые два отправляются по шаблону.

### Важно: контекст для AI

В промпте нужно указать, что первые два сообщения уже отправлены автоматически:

```
Контекст диалога:
- Первые два сообщения (знакомство и вопросы об опыте) уже отправлены автоматически.
- Ты подключаешься к диалогу с третьего сообщения.
- К этому моменту кандидат уже получил вопросы о стеке, опыте и тестовом задании.
- Твоя задача — продолжить диалог естественно, отталкиваясь от ответов кандидата.
```

### Структура хорошего промпта

1. **Роль и личность** — кто AI, как общается
2. **Контекст** — что первые 2 сообщения уже отправлены
3. **Стадии диалога** — что делать на каждом этапе
4. **Инструменты** — когда вызывать sendTestTask, notifyTeam, closeConversation
5. **Запреты** — чего не делать

### Обновление промпта без редеплоя

Через Swagger UI: раздел **Personas** → `PUT /internal/admin/personas/{ref}` → **Try it out** → передайте только `systemPrompt` → **Execute**

Изменения вступают в силу для следующего сообщения. Текущие диалоги продолжатся с новым промптом.

---

## Тестирование через Kafka UI

### Запуск Kafka UI

```bash
docker run -p 8090:8080 \
  -e KAFKA_CLUSTERS_0_NAME=streamabyss \
  -e KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS=kafka-s1-b1.streamabyss.com:32100,kafka-s1-b2.streamabyss.com:32100,kafka-s1-b3.streamabyss.com:32100 \
  -e KAFKA_CLUSTERS_0_PROPERTIES_SECURITY_PROTOCOL=SASL_SSL \
  -e KAFKA_CLUSTERS_0_PROPERTIES_SASL_MECHANISM=SCRAM-SHA-512 \
  -e "KAFKA_CLUSTERS_0_PROPERTIES_SASL_JAAS_CONFIG=org.apache.kafka.common.security.scram.ScramLoginModule required username='KAFKACLUSTER_USERNAME' password='KAFKACLUSTER_PASSWORD';" \
  provectuslabs/kafka-ui
```

Kafka-credentials берутся из Heroku Config Vars (`KAFKACLUSTER_BROKERS`, `KAFKACLUSTER_USERNAME`, `KAFKACLUSTER_PASSWORD`).

### Отправка тестового сообщения

В Kafka UI: **Topics** → `ai.processing.filtered` → **Produce Message**:

```json
{
  "ref": "candidate_java",
  "sourceId": "test-manual-001",
  "aiResult": {
    "matches": true,
    "confidence": 0.9,
    "reason": "5 лет опыта в Java и Spring Boot",
    "full_name": "Иван Иванов",
    "contacts": {
      "telegram": "username_кандидата",
      "phone": null,
      "email": null,
      "linkedin": null
    }
  }
}
```

**Важно:** `sourceId` должен быть уникальным для каждого теста (защита от дублей).

---

## Admin API

Все эндпоинты доступны через **Swagger UI** с интерактивными примерами:

https://project-007-3-ai-communication-10a1ebc2993e.herokuapp.com/swagger-ui.html

### Personas: `/internal/admin/personas`

| Метод | Эндпоинт | Описание |
|---|---|---|
| `GET` | `/internal/admin/personas` | Список всех персон |
| `GET` | `/internal/admin/personas/{ref}` | Персона по ref |
| `POST` | `/internal/admin/personas` | Создать персону |
| `PUT` | `/internal/admin/personas/{ref}` | Обновить (partial update — передавайте только нужные поля) |
| `DELETE` | `/internal/admin/personas/{ref}` | Деактивировать (soft-delete) |
| `PATCH` | `/internal/admin/personas/{ref}/test-task-url` | Обновить URL тестового задания |
| `PATCH` | `/internal/admin/personas/notification-contact` | Обновить контакт для уведомлений (для всех персон) |
| `POST` | `/internal/admin/personas/cache/evict` | Сбросить кеш |

### Conversations: `/internal/admin/conversations`

| Метод | Эндпоинт | Описание |
|---|---|---|
| `GET` | `/internal/admin/conversations` | Список всех диалогов (контакты маскированы) |
| `GET` | `/internal/admin/conversations/{id}` | Диалог с полной историей сообщений |
| `GET` | `/internal/admin/conversations/status/{status}` | Фильтр по статусу: `ACTIVE`, `TEST_SENT`, `COMPLETED`, `FAILED`, `TIMED_OUT` |

### Telegram: `/internal/telegram`

| Метод | Эндпоинт | Описание |
|---|---|---|
| `GET` | `/internal/telegram/status` | Статус авторизации |
| `POST` | `/internal/telegram/otp` | Отправить OTP-код или 2FA-пароль |
| `POST` | `/internal/telegram/test-send` | Тестовая отправка сообщения по @username |

---

## Переменные окружения

### Секреты (обязательные на проде)

| Переменная | Описание | Пример |
|---|---|---|
| `TELEGRAM_API_ID` | Telegram API ID | `20405054` |
| `TELEGRAM_API_HASH` | Telegram API hash | `57da0dd...` |
| `TELEGRAM_PHONE` | Номер телефона | `+79817110577` |
| `OPENAI_API_KEY` | OpenAI API ключ | `sk-proj-...` |
| `DB_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://host:5432/aicomm` |
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
| `OPENAI_TEMPERATURE` | `0.7` | Температура |
| `OPENAI_MAX_TOKENS` | `500` | Макс длина ответа |

### Рабочее время и поведение

| Переменная | Дефолт | Описание |
|---|---|---|
| `WORK_START` | `09:00` | Начало рабочего дня |
| `WORK_END` | `18:35` | Конец рабочего дня |
| `WORK_TIMEZONE` | `Europe/Moscow` | Часовой пояс |
| `WORK_DAYS` | `MONDAY,...,FRIDAY` | Рабочие дни |
| `RATE_LIMIT_FIRST_CONTACT_SEC` | `120` | Мин интервал между первыми контактами (сек) |
| `RATE_LIMIT_MAX_FIRST_CONTACTS_DAY` | `25` | Макс новых контактов в день |
| `AGENT_MEMORY_MAX_MESSAGES` | `20` | Скользящее окно истории AI |
| `TEST_TASK_TIMEOUT_DAYS` | `3` | Дней до напоминания о тестовом задании |

---

## Тесты

```bash
mvn test

# Текущий результат: 70 тестов, все зелёные
```
