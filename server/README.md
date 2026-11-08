# TAGa-Say Server

Слепой ретранслятор зашифрованных конвертов. Не хранит открытый текст и приватные ключи.

## Стек

- Kotlin 2.0 / Ktor 3 / Netty
- PostgreSQL + Redis (следующий этап); сейчас in-memory store с JSON-снапшотом (`TAGASAY_DATA`)
- BouncyCastle для проверки подписей Ed25519
- Caddy (TLS 1.3, HSTS) в docker-compose

## Запуск

```bash
./gradlew run
# http://localhost:8080/health
```

Docker:

```bash
docker compose up --build
```

## API (alpha)

| Метод | Путь | Описание |
|---|---|---|
| GET | `/health` | статус и версия |
| POST | `/v1/auth/challenge` | challenge для Ed25519 |
| POST | `/v1/auth/register` | регистрация устройства |
| POST | `/v1/auth/refresh` | ротация delivery-токена |
| POST | `/v1/envelope` | отправить конверт |
| GET | `/v1/envelope` | забрать конверты |
| POST | `/v1/envelope/ack` | подтвердить доставку |
| GET | `/v1/users/{username}` | публичный профиль и ключ |
| POST | `/v1/discovery/phones` | поиск по хешам номеров |
| GET | `/v1/server/info` | версия и соль сервера |
| GET/POST | `/bot/v1/getMe`, `/bot/v1/getUpdates` | Bot API: бот и updates |
| POST | `/bot/v1/sendMessage`, `editMessageText`, `deleteMessage` | Bot API: сообщения |
| GET/POST | `/bot/v1/getMyCommands`, `setMyCommands`, `deleteMyCommands` | команды |
| POST | `/bot/v1/answerCallbackQuery` | callback |
| GET | `/v1/bots`, `/v1/bots/{username}`, `/v1/bots/mine`, `/v1/bots/inbox` | пользовательский API ботов |
| POST | `/v1/bots/{username}/send`, `/callback`, `/block`, `/token`, `/revoke`, `/update` | управление и чат |

Сервер не принимает открытый текст для пользовательских чатов. Бот-чаты —
осознанно plaintext (ADR-004); контракты — [../docs/bot-api.md](../docs/bot-api.md).
Подробнее: [../docs/protocol.md](../docs/protocol.md).

## Переменные окружения

| Переменная | По умолчанию | |
|---|---|---|
| `TAGASAY_PORT` | `8080` | порт |
| `TAGASAY_DATA` | — | JSON-снапшот пользователей |
| `TAGASAY_BOT_DATA` | `<parent TAGASAY_DATA>/bots` | отдельный BotStore |
| `TAGASAY_BOT_TOKEN_KEY` | dev-ключ | мастер-ключ токенов ботов, ≥32 символов; **обязателен при `TAGASAY_ENV=production`** |
| `TAGASAY_ENV` | — | `production` включает fail-fast без ключа |

## TODO

- PostgreSQL-хранилище и миграции (Flyway).
- Redis Pub/Sub для WebSocket-доставки.
- Ограничение rate-limit по IP и токену.
- S3-хранилище шифрованных блобов.
- Push без содержимого (FCM).
- Метрики Prometheus и healthchecks.
