# Платформа ботов TAGa-Say

> **Статус 1.2.0 (сервер):** ядро платформы и @BotFather работают.
> Реализованы `getMe`, `getUpdates`, `sendMessage`, `editMessageText`,
> `deleteMessage`, команды, callback, `/start`-gate, `BotStore`, файлы
> (`sendPhoto`/`sendDocument`/`sendVoice`/`sendVideo`/`sendAudio`, `getFile`),
> webhook и боты в группах/каналах.
> Подробные контракты — [bot-api.md](bot-api.md), паритет BotFather —
> [botfather.md](botfather.md), безопасность — [bot-security.md](bot-security.md).
> Web App, inline, административные методы комнат (pin/ban/restrict/инвайты) —
> `[~]` (следующие этапы): методы честно возвращают `400 unsupported`.

Боты — первоклассные участники мессенджера: их можно добавить в личный чат, группу или
канал, вызвать командой, они умеют отвечать, редактировать сообщения, присылать файлы и
работать с inline-клавиатурами.

> Бот **не получает доступ к E2EE-ключам** и не читает историю чата. Он видит только те
> сообщения, которые пользователь адресует боту (privacy mode) или в которых бот упомянут.

## 1. Модель

```
Пользователь ──E2EE──► TAGa-Say ──plaintext──► Bot Gateway ──HTTPS──► Bot Server
     ▲                    (relay)                  │
     └──────────E2EE───────────────────────────────┘
```

Сообщения боту расшифровываются на устройстве пользователя и отправляются в bot-gateway
как отдельный тип чата. Это осознанный компромисс: бот — доверенная конечная точка, а не
участник E2EE-сессии.

## 2. Создание бота

1. В приложении: **Настройки → Боты → Создать бота** (или `@BotFather`).
2. Указываются: `@username` (суффикс `bot`), имя, описание, аватар, scopes.
3. Сервер возвращает токен **один раз**:

```
taga_bot_7f3c... .<64-char-secret>
```

Хранится HMAC-SHA256(token) для проверки и AES-256-GCM-шифртекст для показа
владельцу; ключ — `TAGASAY_BOT_TOKEN_KEY` (ADR-005). При утечке токен
отзывается и ротируется. Scopes: `updates:read`, `messages:send`,
`messages:edit`, `messages:delete`, `files:read`, `files:write`, `chats:read`,
`chats:manage`, `webapp`, `inline`.

## 3. Bot API (v1)

Базовый URL: `https://<server>/bot/v1`. Аутентификация: `Authorization: Bearer <token>`.

### 3.1. Long polling

```
GET /getUpdates?offset=<update_id>&limit=100&timeout=30
→ [Update, ...]
```

`Update`:

```json
{
  "update_id": 1042,
  "message": {
    "message_id": "01J...",
    "date": 1762473600,
    "chat": {"id": "g:42", "type": "group", "title": "TAGa-Say Team"},
    "from": {"id": "u:17", "username": "lilanga", "display_name": "Lilanga"},
    "text": "/start",
    "entities": [{"type": "bot_command", "offset": 0, "length": 6}],
    "reply_to_message": null
  }
}
```

Типы в 1.2.0: `message`, `channel_post`, `callback_query`, `my_chat_member`.
Остальные (`edited_message`, `chat_member`, `message_reaction`, …) — `[~]`.

### 3.2. Webhook

`setWebhook`/`deleteWebhook`/`getWebhookInfo` включены: только HTTPS,
SSRF-защита (private/loopback/metadata, повторный DNS), HMAC +
`X-TAGa-Say-Bot-Api-Secret-Token`, таймаут 5 с, 3 ретрая, `last_error`,
409 при активном `getUpdates`. Полный контракт —
[bot-api.md](bot-api.md) §3.9, безопасность — [bot-security.md](bot-security.md) §6.

### 3.3. Методы

| Метод | Статус | Назначение |
|---|---|---|
| `getMe` | ✅ | информация о боте |
| `getUpdates` | ✅ | long polling |
| `setWebhook` / `deleteWebhook` / `getWebhookInfo` | ✅ | HTTPS, SSRF, HMAC, ретраи |
| `sendMessage` | ✅ | текст, `parse_mode`, `reply_markup` |
| `editMessageText` | ✅ | редактирование своего сообщения |
| `deleteMessage` | ✅ | удаление своего сообщения |
| `sendPhoto` / `sendVideo` / `sendDocument` / `sendAudio` / `sendVoice` | ✅ | multipart или `file_id`, MIME, квоты |
| `getFile` | ✅ | подписанная ссылка TTL 1 час |
| `sendChatAction` | `[~]` | следующая версия |
| `answerCallbackQuery` | ✅ | ответ на нажатие inline-кнопки |
| `setMyCommands` / `getMyCommands` | ✅ | меню команд |
| `banChatMember` / `restrictChatMember` / `pinChatMessage` | `[~]` | права хранятся, методы следующие |

`sendMessage`:

```json
{
  "chat_id": "g:42",
  "text": "Привет, <b>мир</b>",
  "parse_mode": "HTML",
  "disable_web_page_preview": true,
  "reply_to_message_id": "01J...",
  "reply_markup": {
    "inline_keyboard": [[
      {"text": "Открыть", "url": "https://example.com"},
      {"text": "Ок", "callback_data": "ack:1"}
    ]]
  }
}
```

`callback_data` ≤ 64 байт. Для длинных данных — короткий ID и состояние на стороне бота.

## 4. Deep links

- `taga://bot?start=<payload>` — открыть бота и отправить `/start <payload>`.
- `https://<server>/bot/<username>?start=<payload>` — universal link.
- `taga://bot/<username>` — карточка бота.

`payload` ≤ 64 символов `[A-Za-z0-9_-]`.

## 5. Приватность и безопасность

- **Privacy mode по умолчанию**: бот получает только команды, упоминания и ответы на свои
  сообщения. Полный доступ к сообщениям — отдельный scope и явное предупреждение.
- Бот видит plaintext только тех сообщений, которые ему адресованы; ключи чата ему
  недоступны.
- Все сообщения бота помечаются бейджем «бот»; пользователь может заблокировать бота.
- Токены: HMAC для проверки + AES-256-GCM для показа, ротация, отзыв,
  audit без секрета (ADR-005).
- Webhook: только HTTPS, запрет приватных IP (SSRF), проверка HMAC, pinning
  по желанию — включено; остаточное окно DNS rebinding описано в ADR-007.
- Rate limits: 30 msg/s на бота, 1 msg/s в один чат, 20 запросов/мин на updates;
  `429 {"retry_after": N}`.
- Файлы бота проходят тот же клиентский антивирус у получателя.
- Логи gateway не содержат текст сообщений (только метаданные и хеши).

## 6. UI в приложении

- Каталог ботов и поиск `@...bot`.
- Карточка бота: описание, команды, «Добавить в чат», «Остановить».
- В чате: кнопка «Меню» с командами, inline-клавиатура под сообщением.
- Настройки бота для владельца: токен, webhook, scopes, статистика, отзыв.

## 7. Этапы

1. ✅ Модель ботов, токены, scopes, BotFather.
2. ✅ `getMe`, `getUpdates`, `sendMessage`, `editMessageText`,
   `deleteMessage`, `setMyCommands`.
3. ✅ Webhook + подпись + ретраи + SSRF.
4. ✅ Inline-клавиатуры (сервер принимает), callback, файлы.
5. `[~]` Каталог, модерация; rate limits ✅, аудит ✅.
6. `[~]` Первый официальный бот — **Enchat Bridge** (см. `docs/enchat-bridge.md`).
