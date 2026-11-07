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

Сервер не принимает открытый текст. `recipient` — blinded delivery token, выданный устройству.
Подробнее: [../docs/protocol.md](../docs/protocol.md).

## Переменные окружения

| Переменная | По умолчанию | |
|---|---|---|
| `TAGASAY_PORT` | `8080` | порт |

## TODO

- PostgreSQL-хранилище и миграции (Flyway).
- Redis Pub/Sub для WebSocket-доставки.
- Ограничение rate-limit по IP и токену.
- S3-хранилище шифрованных блобов.
- Push без содержимого (FCM).
- Метрики Prometheus и healthchecks.
