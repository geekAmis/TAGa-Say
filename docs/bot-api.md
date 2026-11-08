# Bot API v1 — контракты (сервер 1.2.0)

Статус: **ядро платформы ботов**, релиз 1.4.0 / целевая версия сервера 1.2.0.
Машиночитаемый скелет: [`server/openapi/bot-api.yaml`](../server/openapi/bot-api.yaml).

Реализовано: `getMe`, `getUpdates`, `sendMessage`, `editMessageText`,
`deleteMessage`, `getMyCommands`, `setMyCommands`, `deleteMyCommands`,
`answerCallbackQuery`, файлы (`sendPhoto`, `sendDocument`, `sendVoice`,
`sendVideo`, `sendAudio`, `getFile`, подписанное скачивание) и webhook
(`setWebhook`, `deleteWebhook`, `getWebhookInfo`), боты в группах/каналах.
Всё остальное честно возвращает `400 unsupported` и помечено `[~]` ниже.

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
нормализует к `u:alice`. Для бота в комнате — `g:<roomId>`/`c:<roomId>`;
бот должен быть добавлен в комнату, публикация в канал требует права
`post_messages` (иначе 403). Бот в группе с privacy mode получает только
адресованные сообщения (см. §4).

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
- `allowed_updates` фильтрует типы: `message`, `channel_post`,
  `callback_query`, `my_chat_member`, `edited_message`, `chat_member`,
  `message_reaction` (последние три зарезервированы, сейчас не генерируются).
- Если активен webhook — **409 Conflict**; сначала `deleteWebhook`.
- `drop_pending_updates` — в `setWebhook`/`deleteWebhook`.

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
- `chat_id` — `u:<username>` либо `g:<roomId>`/`c:<roomId>`. В личке бот
  должен иметь право писать (см. `/start`-gate); в комнате — быть добавлен,
  в канале — иметь `post_messages`. `reply_to_message_id` не проверяется на
  существование (как в Telegram) — клиент просто не покажет ответ, если
  сообщения нет.
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

### 3.8. Файлы

`POST /bot/v1/sendPhoto`, `sendDocument`, `sendVoice`, `sendVideo`, `sendAudio`
(scope `files:write`). Два варианта тела:

1. **multipart/form-data**:

```
chat_id=u:alice
caption=Подпись (≤1024)
parse_mode=HTML
reply_to_message_id=m_...
reply_markup={"inline_keyboard":[...]}
photo / document / voice / video / audio = <файл>
```

2. **JSON** с ранее выданным `file_id`:

```json
{"chat_id": "u:alice", "file_id": "file_ab12...", "caption": "Подпись"}
```

Ограничения и проверки:

- 50 МБ на файл; квота бота 1 ГБ, uploader 100 МБ/сутки; TTL 30 дней.
- MIME определяется по magic bytes, не по имени/расширению:
  `photo` — JPEG/PNG/WebP/GIF; `voice` — OGG/MP3/M4A/WAV;
  `video` — MP4/WebM; `document` — любой (HTML/SVG не отдаются inline).
  Несоответствие → `415`; превышение размера/квоты → `413`.
- `file_id` опаковый и привязан к боту: чужой/просроченный → `404`.
  Нельзя подставить `file_id` другого media-типа → 400.
- Имя из запроса не используется как путь; хранится случайное имя,
  расширение — по MIME; `..`, абсолютные пути и symlink запрещены.
- Ответ — сообщение с `photo`/`document`/`voice`/`video`/`audio`:

```json
{
  "ok": true,
  "result": {
    "message_id": "m_1a2b3c",
    "date": 1794100001,
    "chat": {"id": "u:alice", "type": "private", "username": "alice"},
    "from": {"id": "b:my_helper_bot", "username": "my_helper_bot", "display_name": "My Helper", "is_bot": true},
    "caption": "Подпись",
    "photo": [{"file_id": "file_ab12", "file_unique_id": "fq_9f8e", "file_size": 2048, "mime_type": "image/png", "file_name": "photo.png"}]
  }
}
```

`GET|POST /bot/v1/getFile?file_id=...` (scope `files:read`) возвращает:

```json
{
  "file_id": "file_ab12", "file_unique_id": "fq_9f8e",
  "file_size": 2048, "mime_type": "image/png", "file_name": "photo.png",
  "kind": "photo",
  "file_url": "https://relay.example/bot/v1/file/file_ab12?expires=1794103600&sig=..."
}
```

`GET /bot/v1/file/{fileId}?expires=...&sig=...` — скачивание по подписанной
ссылке (без Bearer). Подпись HMAC, TTL 1 час; `Content-Disposition:
attachment`, `X-Content-Type-Options: nosniff`; HTML/SVG/XML отдаются как
`application/octet-stream`. Битые/истёкшие ссылки → 403.

### 3.9. Webhook

`POST /bot/v1/setWebhook`:

```json
{
  "url": "https://bot.example/taga",
  "secret_token": "optional-secret",
  "allowed_updates": ["message", "callback_query"],
  "drop_pending_updates": false,
  "max_connections": 10
}
```

- Только HTTPS (HTTP — лишь в тестовом режиме), порт 443/8443, без userinfo,
  fragment и redirect.
- SSRF: DNS резолвится при установке и **повторно перед каждой доставкой**;
  loopback/private/link-local/multicast/CGNAT/`169.254.169.254`/IPv6 ULA
  запрещены → 400.
- `secret_token` 1…256, хранится AES-GCM; `max_connections` 1…10;
  `allowed_updates` — известные типы.
- `drop_pending_updates=true` очищает очередь.

Доставка:

```
POST <url>
Content-Type: application/json
X-TAGa-Say-Timestamp: 1794100000
X-TAGa-Say-Signature: sha256=HMAC_SHA256(secret, "<timestamp>.<body>")
X-TAGa-Say-Bot-Api-Secret-Token: <secret>   // если задан
```

- Таймаут 5 с, redirect не выполняется (3xx = ошибка), 3 ретрая с
  экспоненциальной задержкой (2/8/20 с); после — `last_error`.
- Updates не теряются: при ошибке остаются в очереди, `pending_update_count`
  растёт; успешная доставка (2xx) подтверждает update.
- Пока webhook активен, `getUpdates` → **409 Conflict**.
- `POST /bot/v1/deleteWebhook` `{"drop_pending_updates": true}` удаляет
  webhook и (опционально) очередь.
- `GET|POST /bot/v1/getWebhookInfo` →

```json
{
  "url": "https://bot.example/taga",
  "has_custom_certificate": false,
  "pending_update_count": 0,
  "max_connections": 10,
  "allowed_updates": ["message"],
  "last_error_message": "",
  "last_error_date": 0
}
```

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

`getMyName`, `setMyName`, `getMyDescription`, `setMyDescription`,
`getMyShortDescription`, `setMyShortDescription`, `setMyProfilePhoto`,
`removeMyProfilePhoto`, `setChatMenuButton`, `getChatMenuButton`,
`getMyDefaultAdministratorRights`, `setMyDefaultAdministratorRights`,
`forwardMessage`, `forwardMessages`, `copyMessage`, `copyMessages`,
`sendAnimation`, `sendVideoNote`, `sendMediaGroup`, `sendLocation`,
`sendVenue`, `sendContact`, `sendPoll`, `sendDice`, `sendSticker`,
`sendChatAction`, `setMessageReaction`, `editMessageCaption`,
`editMessageMedia`, `editMessageReplyMarkup`, `deleteMessages`, `stopPoll`,
`answerInlineQuery`, `answerWebAppQuery`, все `getChat*`/`*ChatMember*`/
`pinChatMessage`/`ban`/`restrict`/инвайты/join requests.

Причина: этапы 6 (Web App/inline) и административные методы комнат не входили
в этот batch. Права `pin/ban/restrict/invite` уже хранятся в `RoomBot.rights`,
но методы ещё не включены. Никакой имитации: неподдержанный метод явно
отвечает 400.

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
| GET | `/v1/rooms/{id}/bots` | боты комнаты (участник/канал) |
| POST | `/v1/rooms/{id}/bots` | добавить бота (owner/admin) |
| DELETE | `/v1/rooms/{id}/bots/{username}` | удалить бота (owner/admin) |
| POST | `/v1/rooms/{id}/bots/{username}/rights` | изменить права (owner/admin) |

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

- `text` 1…4096; `attachment_id` (пользовательские файлы) в этом batch → 400
  unsupported `[~]`; файлы от бота пользователю работают.
- `chat_id` — свой `u:<username>` или `g:<roomId>`/`c:<roomId>`.
  Для комнаты пользователь должен быть участником, бот — добавлен; в канале
  бот должен иметь `post_messages` (иначе 403).
- Privacy mode: в группе неадресованное сообщение принимается, но боту не
  уходит и plaintext-копия не сохраняется — ответ `delivered:false`.
  Команда (`/...`), упоминание `@bot` или `reply_to` на сообщение бота
  доставляются; при `privacy_mode=false` — все.
- Для BotFather — 20 команд/мин на владельца.
- Ответ: `{"accepted":true,"delivered":true,"message_id":"m_...","client_msg_id":"...","reply_message_ids":["m_..."]}`
  (`message_id` пустой, если сообщение не доставлено боту).
- Ошибки — обычный `{"error":"...","code":"..."}` (не Bot API-конверт):
  400/403/404/429.

### 7.2. Inbox (long-poll)

`GET /v1/bots/inbox?since=<event_id>&timeout=30&limit=100`

- `event_id` монотонный курсор; клиент сохраняет последний и передаёт в
  `since`. Пустой ответ — `[]`.
- События: `bot_message`, `bot_message_edited`, `bot_messages_deleted`,
  `callback_answer`. Для комнат `chat_id` = `g:<roomId>`/`c:<roomId>`.
- Новые участники комнаты не получают старые бот-сообщения: сервер
  фильтрует по времени вступления (`Room.memberSince`); курсор `since`
  дополнительно ограничивает выдачу.
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
    "deleted_at": 0,
    "attachment": {
      "file_id": "file_ab12", "file_unique_id": "fq_9f8e", "kind": "photo",
      "file_name": "photo.png", "mime_type": "image/png", "file_size": 2048,
      "file_url": "https://relay.example/bot/v1/file/file_ab12?expires=...&sig=..."
    }
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

### 7.4. Боты в комнатах

`POST /v1/rooms/{id}/bots`:

```json
{
  "username": "news_bot",
  "rights": {
    "post_messages": true,
    "delete_messages": true,
    "pin_messages": false
  }
}
```

- Только owner/admin комнаты; иначе 403. Бот не может добавить себя сам.
- Для группы бот должен разрешать группы (`can_join_groups`), иначе 400.
- Права: `change_info`, `delete_messages`, `ban_users`, `invite_users`,
  `pin_messages`, `promote_members`, `manage_chat`, `post_messages`.
  В этом batch сервер проверяет `post_messages` для канала; остальные права
  хранятся и будут использованы административными методами (`[~]`).
- `DELETE /v1/rooms/{id}/bots/{username}` — удалить (owner/admin).
- `POST /v1/rooms/{id}/bots/{username}/rights` — заменить права.
- `GET /v1/rooms/{id}/bots` — список (участник группы/канала).

События боту:

- добавление/удаление/смена прав → `my_chat_member`
  (`old_chat_member`/`new_chat_member`, `chat`, `from`, `date`);
- пост пользователя в канал, адресованный боту (через
  `POST /v1/bots/{username}/send` с `chat_id=c:...`) → `channel_post`;
- сообщение в группе → `message` и только если оно адресовано боту
  (privacy mode) или privacy off.

Ответы бота в комнате становятся plaintext для участников и приходят им через
`/v1/bots/inbox` с `chat_id=g:`/`c:`; люди при этом продолжают получать
обычные E2EE-конверты от клиента.

## 8. Ретеншн и хранение

| Данные | Срок/лимит |
|---|---|
| updates | 10 000 на бота, 7 дней |
| сообщения бот-чатов | 30 дней, 5 000 на бота |
| callback-ответы | 1 000 в памяти/снапшоте |
| удалённый бот | статус `deleted`, данные до 30 дней |
| файлы | 50 МБ/файл, 30 дней, квота 1 ГБ на бота и 100 МБ/сутки на uploader |
| ссылка на файл | подпись HMAC, 1 час |

Файлы: `bots.json`, `bot-updates.jsonl`, `bot-chats.jsonl`,
`bot-files/index.json` в `TAGASAY_BOT_DATA` (или
`<parent TAGASAY_DATA>/bots`), права `600`, атомарная замена. Секреты
хранятся как HMAC + AES-GCM (ADR-005).

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

# 6. Файл боту-пользователю (multipart)
curl -s -X POST http://localhost:8080/bot/v1/sendPhoto \
  -H "Authorization: Bearer $BOT_TOKEN" \
  -F chat_id=u:alice \
  -F caption='Фото' \
  -F photo=@photo.png

# 7. Метаданные и подписанная ссылка
curl -s "http://localhost:8080/bot/v1/getFile?file_id=file_..." \
  -H "Authorization: Bearer $BOT_TOKEN"

# 8. Webhook (публичный HTTPS)
curl -s -X POST http://localhost:8080/bot/v1/setWebhook \
  -H "Authorization: Bearer $BOT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://bot.example/taga","secret_token":"s3cret","allowed_updates":["message"]}'
```
