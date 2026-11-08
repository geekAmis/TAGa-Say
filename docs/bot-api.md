# Bot API v1 — контракты (сервер 1.2.0)

Статус: **ядро платформы ботов**, релиз 1.4.0 / целевая версия сервера 1.2.0.
Машиночитаемый скелет: [`server/openapi/bot-api.yaml`](../server/openapi/bot-api.yaml).
Реализовано: `getMe`, `getUpdates`, `sendMessage`, `editMessageText`,
`deleteMessage`, `getMyCommands`, `setMyCommands`, `deleteMyCommands`,
`answerCallbackQuery`. Всё остальное честно возвращает
`400 unsupported` и помечено `[~]` ниже — без имитации.

- Base URL: `https://<server>/bot/v1`
- Транспорт: HTTPS (HTTP допустим только для localhost/debug).
- Аутентификация: `Authorization: Bearer <bot-token>` **только в заголовке**.
  Токен в query/path запрещён и не логируется.
- Формат: JSON, `Content-Type: application/json` для POST.
- Токен: `taga_bot_<numericId>.<base64url-secret>` (секрет 32 байта).
  Выдаётся BotFather при создании и по `/revoke`; старый отзывается сразу.

> Бот-чаты **не E2EE** (ADR-004). Бот видит только адресованные ему сообщения,
> не получает ключи, `/v1/state`, контакты, телефоны, историю и чужие чаты.

## 1. Ответы и ошибки

Успех:

```json
{"ok": true, "result": { }}
```

Ошибка:

```json
{
  "ok": false,
  "error_code": 403,
  "description": "bot can't initiate conversation (user has not sent /start)",
  "parameters": {"retry_after": 5}
}
```

| Код | Когда |
|---|---|
| 400 | неверный JSON, поля, parse_mode, entities, reply_markup, unsupported |
| 401 | нет токена, битый токен, токен отозван, бот удалён |
| 403 | нет scope, бот не участник, пользователь не сделал `/start`, `/stop`/блокировка |
| 404 | чат/сообщение не найдено |
| 409 | зарезервировано под конфликт webhook/polling `[~]` |
| 413/415 | лимиты загрузки `[~]` (файлы не включены) |
| 429 | rate limit; `parameters.retry_after` в секундах |

## 2. Идентификаторы

| Префикс | Значение |
|---|---|
| `u:<username>` | пользователь |
| `b:<username>` | бот (в ответах `from.id`) |
| `g:<roomId>` | группа `[~]` в этом релизе |
| `c:<roomId>` | канал `[~]` в этом релизе |

`chat_id` можно передать как `u:alice`, `@alice` или `alice` — сервер
нормализует к `u:alice`. `g:`/`c:` в 1.2.0 возвращают
`403 bot rooms are not enabled in this release` (добавление ботов в комнаты —
следующий batch, §7.5 ТЗ).

## 3. Методы

### 3.1. getMe

`GET /bot/v1/getMe` (POST тоже принимается). Scope не нужен.

```bash
curl -s https://relay.example/bot/v1/getMe \
  -H "Authorization: Bearer $BOT_TOKEN"
```

```json
{
  "ok": true,
  "result": {
    "id": 7,
    "is_bot": true,
    "username": "my_helper_bot",
    "display_name": "My Helper",
    "about": "Кратко",
    "description": "Экран до /start",
    "verified": false,
    "capabilities": {
      "can_join_groups": true,
      "privacy_mode": true,
      "supports_inline_queries": false,
      "web_app": false
    },
    "scopes": ["updates:read", "messages:send", "messages:edit", "messages:delete", "files:read", "files:write", "chats:read"],
    "commands": [{"command": "start", "description": "Начать"}]
  }
}
```

### 3.2. getUpdates

`GET /bot/v1/getUpdates?offset=<id>&limit=1..100&timeout=0..50&allowed_updates=message,callback_query`
(POST с теми же query-параметрами тоже принимается.)

- `update_id` монотонно растёт в пределах бота.
- `offset` подтверждает все updates с `update_id < offset`; они удаляются.
  Пока offset не передан, сервер повторяет те же updates (семантика Telegram).
- Long poll до 50 с; пустой ответ — `"result": []`.
- Лимит: **20 запросов/мин на бота** → 429.
- Хранение: 10 000 updates / 7 дней на бота (ring buffer). После рестарта
  сервера offset и updates сохраняются (JSONL).
- `allowed_updates` фильтрует типы: `message`, `callback_query`.
- Webhook в 1.2.0 не включается, поэтому `409` пока недостижим `[~]`.
- `drop_pending_updates` `[~]` (setWebhook не реализован).

```bash
curl -s "https://relay.example/bot/v1/getUpdates?offset=0&timeout=25&limit=100" \
  -H "Authorization: Bearer $BOT_TOKEN"
```

```json
{
  "ok": true,
  "result": [{
    "update_id": 42,
    "message": {
      "message_id": "m_9f1c...",
      "date": 1794100000,
      "chat": {"id": "u:alice", "type": "private", "username": "alice"},
      "from": {"id": "u:alice", "username": "alice", "display_name": "alice", "is_bot": false},
      "text": "/start"
    }
  }]
}
```

`callback_query`:

```json
{
  "update_id": 43,
  "callback_query": {
    "id": "cq_1a2b3c",
    "from": {"id": "u:alice", "username": "alice", "display_name": "alice", "is_bot": false},
    "chat_instance": "u:alice",
    "data": "ack:1",
    "message": {"message_id": "m_9f1c...", "date": 1794100000, "chat": {"id": "u:alice", "type": "private", "username": "alice"}, "from": {"id": "b:my_helper_bot", "username": "my_helper_bot", "display_name": "My Helper", "is_bot": true}, "text": "Выберите", "reply_markup": {"inline_keyboard": [[{"text": "Ок", "callback_data": "ack:1"}]]}}
  }
}
```

### 3.3. sendMessage

`POST /bot/v1/sendMessage`, scope `messages:send`.

```json
{
  "chat_id": "u:alice",
  "text": "Привет, <b>мир</b>",
  "parse_mode": "HTML",
  "disable_web_page_preview": true,
  "reply_to_message_id": "m_...",
  "reply_markup": {
    "inline_keyboard": [[
      {"text": "Открыть", "url": "https://example.com"},
      {"text": "Ок", "callback_data": "ack:1"}
    ]]
  }
}
```

Правила:

- `text` — 1…4096 символов **после** разбора entities.- `parse_mode`: `HTML`, `MarkdownV2` (базовый subset) или без него.
  Хранится plain text + `entities` (смещения UTF-16). Неизвестные/незакрытые
  теги → 400. HTML: `b/strong`, `i/em`, `u`, `s/strike/del`, `code`, `pre`,
  `a href`. MarkdownV2: `*bold*`, `_italic_`, `__underline__`, `~strike~`,
  `` `code` ``, ```` ```pre``` ````, `[text](url)`. Вложенность MarkdownV2
  не поддерживается — задокументировано, это `[~]`, а не скрытая имитация.
- `entities` можно передать явно вместо `parse_mode`; проверяются тип,
  диапазоны, ≤100 entities.
- `reply_markup`: `inline_keyboard` (до 100 кнопок), `keyboard`,
  `remove_keyboard`, `force_reply`. Reply-клавиатуры сервер принимает и хранит,
  но клиентский рендер — P1 `[~]`. Кнопка: ровно одно из `url` (http/https),
  `callback_data` (1…64 байта UTF-8), `web_app`.
- `web_app` — только личный чат и только домен, зарегистрированный за ботом
  (`bot.domain`). В 1.2.0 `/setdomain` не реализован, поэтому `web_app`-кнопки
  на практике получают 400 `[~]`.
- `chat_id` — только `u:<username>`; бот должен иметь право писать
  (см. `/start`-gate). `reply_to_message_id` не проверяется на существование
  (как в Telegram) — клиент просто не покажет ответ, если сообщения нет.
- Лимиты: **30 сообщений/с на бота**, **1 сообщение/с в один чат** → 429.
  Счётчик чата расходуется только на успешную отправку.

Ответ — сообщение:

```json
{
  "ok": true,
  "result": {
    "message_id": "m_1a2b3c4d5e",
    "date": 1794100001,
    "chat": {"id": "u:alice", "type": "private", "username": "alice"},
    "from": {"id": "b:my_helper_bot", "username": "my_helper_bot", "display_name": "My Helper", "is_bot": true},
    "text": "Привет, мир",
    "entities": [{"type": "bold", "offset": 7, "length": 3}]
  }
}
```

### 3.4. editMessageText

`POST /bot/v1/editMessageText`, scope `messages:edit`.

```json
{
  "chat_id": "u:alice",
  "message_id": "m_1a2b3c4d5e",
  "text": "Новый текст",
  "parse_mode": "HTML",
  "reply_markup": {"inline_keyboard": [[{"text": "Ок", "callback_data": "ok"}]]}
}
```

- Редактировать можно только сообщения бота (`direction=out`), не удалённые.
- Если `reply_markup` не передан (или `null`) — клавиатура сохраняется.
  Чтобы убрать кнопки, передайте `{"inline_keyboard": []}` — сейчас пустая
  клавиатура отклоняется 400, поэтому удаление кнопок `[~]`.
- Ответ — обновлённое сообщение; клиент получает событие
  `bot_message_edited` через `/v1/bots/inbox`.

### 3.5. deleteMessage

`POST /bot/v1/deleteMessage`, scope `messages:delete`.

```json
{"chat_id": "u:alice", "message_id": "m_1a2b3c4d5e"}
```

- Только свои сообщения бота; отметка `deleted_at`, текст не стирается из
  JSONL до истечения ретеншна (нужен tombstone для клиента).
- Ответ: `{"ok": true, "result": true}`.
- Клиент получает `bot_messages_deleted`.

### 3.6. getMyCommands / setMyCommands / deleteMyCommands

`GET|POST /bot/v1/getMyCommands` — возвращает массив команд бота.

`POST /bot/v1/setMyCommands`:

```json
{"commands": [{"command": "start", "description": "Начать"}, {"command": "help", "description": "Помощь"}]}
```

- До 100 команд, `command` — `[a-z0-9_]{1,32}`, описание 1…256, дубликаты → 400.
- `scope` и `language_code` принимаются, но в 1.2.0 **игнорируются**
  (глобальный список) — `[~]`, зафиксировано в доках.
- `deleteMyCommands` очищает список (эквивалент `setMyCommands` с пустым
  массивом).

### 3.7. answerCallbackQuery

`POST /bot/v1/answerCallbackQuery`:

```json
{"callback_query_id": "cq_1a2b3c", "text": "Готово", "show_alert": false}
```

- `callback_query_id` живёт 15 минут и одноразовый; после ответа/истечения —
  400 `query is too old or unknown`.
- `text` ≤ 200; `url`/`cache_time` принимаются, но не используются `[~]`.
- Ответ пользователю попадает в `/v1/bots/inbox` событием `callback_answer`
  (`text`, `show_alert`).

## 4. `/start`-gate, `/stop` и блокировка

- `sendMessage` в `u:<user>` до первого `/start` → **403**
  `bot can't initiate conversation (user has not sent /start)`.
- Пользователь отправляет `/start` через `POST /v1/bots/{username}/send`;
  сервер фиксирует `bot_user_started` и снимает блокировку.
- `/stop` ставит блокировку; последующие `sendMessage` → **403**
  `user blocked the bot`. Повторный `/start` разблокирует.
- Кнопки `POST /v1/bots/{username}/block` / `DELETE .../block` делают то же
  из UI; блокировка сильнее любых scopes.
- Клиент при первом открытии бот-чата обязан отправить `/start`.

## 5. Идемпотентность и лимиты

- `X-Idempotency-Key` (1…128 символов) для `sendMessage`, `editMessageText`,
  `deleteMessage`: дедуп 5 минут, повтор возвращает тот же `result`.
  Кэш в памяти — после рестарта повтор может создать второе сообщение.
- Scope-проверка на каждом методе: `updates:read`, `messages:send`,
  `messages:edit`, `messages:delete`, `files:read`, `files:write`,
  `chats:read`, `chats:manage`, `webapp`, `inline`.
- Прочие лимиты: 20 `getUpdates`/мин, 60 user-send/мин, 20 команд BotFather/мин
  на владельца.

## 6. Не реализовано в 1.2.0 (`[~]`)

Возвращают `400 method '<name>' is not supported in this server release`:

`setWebhook`, `deleteWebhook`, `getWebhookInfo`, `getFile`, `getMyName`,
`setMyName`, `getMyDescription`, `setMyDescription`, `getMyShortDescription`,
`setMyShortDescription`, `setMyProfilePhoto`, `removeMyProfilePhoto`,
`setChatMenuButton`, `getChatMenuButton`, `getMyDefaultAdministratorRights`,
`setMyDefaultAdministratorRights`, `forwardMessage`, `forwardMessages`,
`copyMessage`, `copyMessages`, `sendPhoto`, `sendAudio`, `sendDocument`,
`sendVideo`, `sendAnimation`, `sendVoice`, `sendVideoNote`, `sendMediaGroup`,
`sendLocation`, `sendVenue`, `sendContact`, `sendPoll`, `sendDice`,
`sendSticker`, `sendChatAction`, `setMessageReaction`, `editMessageCaption`,
`editMessageMedia`, `editMessageReplyMarkup`, `deleteMessages`, `stopPoll`,
`answerInlineQuery`, `answerWebAppQuery`, все `getChat*`/`*ChatMember*`/
`pinChatMessage`/инвайты/join requests.

Причина: этап 2 (ядро) по ТЗ; файлы, webhook, Web App, inline и комнаты —
этапы 3–6. Никакой имитации: неподдержанный метод явно отвечает 400.

## 7. Пользовательский API `/v1/bots` (для Android)

Авторизация — access token пользователя (`Authorization: Bearer ...`), не
бот-токен.

| Метод | Путь | Назначение |
|---|---|---|
| GET | `/v1/bots?query=` | поиск/каталог (до 50) |
| GET | `/v1/bots/{username}` | карточка бота |
| GET | `/v1/bots/mine` | мои боты (владелец) |
| POST | `/v1/bots` | создать бота `{name,username}` → 201 `{bot,token}` |
| POST | `/v1/bots/{username}/send` | сообщение боту |
| GET | `/v1/bots/inbox?since=&timeout=0..30&limit=1..100` | long-poll событий |
| POST | `/v1/bots/{username}/callback` | нажатие inline-кнопки |
| POST | `/v1/bots/{username}/block` | заблокировать |
| DELETE | `/v1/bots/{username}/block` | разблокировать |
| GET | `/v1/bots/{username}/stats` | `[~]` 400 unsupported |
| POST | `/v1/bots/{username}/token` | показать токен (только владелец) |
| POST | `/v1/bots/{username}/revoke` | отозвать и вернуть новый токен |
| POST | `/v1/bots/{username}/update` | имя/описание/приватность/группы/inline |
| DELETE | `/v1/bots/{username}` | удалить (только владелец) |
| POST | `/v1/bots/{username}/webapp/session` | `[~]` 400 unsupported |
| POST | `/v1/bots/{username}/inline` | `[~]` 400 unsupported |

Карточка:

```json
{
  "username": "my_helper_bot",
  "display_name": "My Helper",
  "about": "",
  "description": "",
  "verified": false,
  "builtin": false,
  "status": "active",
  "privacy_mode": true,
  "can_join_groups": true,
  "supports_inline_queries": false,
  "domain": "",
  "created_at": 1794100000000,
  "commands": [{"command": "start", "description": "Начать"}],
  "started": true,
  "blocked": false
}
```

### 7.1. Отправка боту

`POST /v1/bots/{username}/send`:

```json
{
  "text": "Привет",
  "client_msg_id": "01J...",
  "chat_id": "u:alice",
  "reply_to": "m_..."
}
```

- `text` 1…4096; `attachment_id` в 1.2.0 → 400 unsupported `[~]`.
- `chat_id` — только свой `u:<username>`; комнаты `[~]`.
- Для BotFather — 20 команд/мин на владельца.
- Ответ: `{"accepted":true,"message_id":"m_...","client_msg_id":"...","reply_message_ids":["m_..."]}`
- Ошибки — обычный `{"error":"...","code":"..."}` (не Bot API-конверт):
  400/403/404/429.

### 7.2. Inbox (long-poll)

`GET /v1/bots/inbox?since=<event_id>&timeout=30&limit=100`

- `event_id` монотонный курсор; клиент сохраняет последний и передаёт в
  `since`. Пустой ответ — `[]`.
- События: `bot_message`, `bot_message_edited`, `bot_messages_deleted`,
  `callback_answer`.
- Дубликаты в пределах одного `since` возможны только при повторном запросе
  без курсора; после `since` = последний `event_id` дублей нет.

```json
[{
  "event_id": 12,
  "type": "bot_message",
  "chat_id": "u:alice",
  "message": {
    "message_id": "m_1a2b3c",
    "chat_id": "u:alice",
    "bot_username": "my_helper_bot",
    "from": "my_helper_bot",
    "is_bot": true,
    "text": "Готово",
    "entities": [],
    "reply_markup": null,
    "reply_to": "",
    "date": 1794100001,
    "edited_at": 0,
    "deleted_at": 0
  }
}]
```

### 7.3. Callback

`POST /v1/bots/{username}/callback`:

```json
{"message_id": "m_1a2b3c", "data": "ack:1", "callback_query_id": ""}
```

- Сервер проверяет, что сообщение принадлежит этому боту и этому чату, а
  `data` реально есть в `reply_markup` этого сообщения → иначе 400.
- `callback_query_id` необязателен: клиент может не знать серверный ID.
- Для внешнего бота создаётся update `callback_query`; для BotFather действие
  выполняется сразу, ответы приходят в inbox.

## 8. Ретеншн и хранение

| Данные | Срок/лимит |
|---|---|
| updates | 10 000 на бота, 7 дней |
| сообщения бот-чатов | 30 дней, 5 000 на бота |
| callback-ответы | 1 000 в памяти/снапшоте |
| удалённый бот | статус `deleted`, данные до 30 дней |
| файлы | `[~]` (P1): 50 МБ, 30 дней |

Файлы: `bots.json`, `bot-updates.jsonl`, `bot-chats.jsonl` в
`TAGASAY_BOT_DATA` (или `<parent TAGASAY_DATA>/bots`), права `600`,
атомарная замена. Секреты хранятся как HMAC + AES-GCM (ADR-005).

## 9. Быстрый E2E на локальном сервере

```bash
# 1. Пользователь создаёт бота (access token пользователя)
curl -s -X POST http://localhost:8080/v1/bots \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"name":"Echo","username":"echo_bot"}'
# → {"bot":{...},"token":"taga_bot_1...."}

export BOT_TOKEN='taga_bot_1....'

# 2. Пользователь открывает чат с ботом
curl -s -X POST http://localhost:8080/v1/bots/echo_bot/send \
  -H "Authorization: Bearer $USER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"text":"/start"}'

# 3. Бот читает updates
curl -s "http://localhost:8080/bot/v1/getUpdates?offset=0&timeout=0" \
  -H "Authorization: Bearer $BOT_TOKEN"

# 4. Бот отвечает
curl -s -X POST http://localhost:8080/bot/v1/sendMessage \
  -H "Authorization: Bearer $BOT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"chat_id":"u:alice","text":"Привет, <b>мир</b>","parse_mode":"HTML"}'

# 5. Приложение забирает ответ
curl -s "http://localhost:8080/v1/bots/inbox?since=0&timeout=0" \
  -H "Authorization: Bearer $USER_TOKEN"
```
