# Архитектура

## 1. Обзор

```
┌──────────────────────────┐          ┌───────────────────────────┐
│   Android-клиент         │          │   Сервер (не доверенный)  │
│                          │          │                           │
│  UI (Compose)            │  TLS 1.3 │  Gateway (Ktor/WS)        │
│  Domain / UseCases       │◄────────►│  REST API                 │
│  Crypto (libsignal)      │  pinning │  Delivery / Mailbox       │
│  Local DB (SQLCipher)    │          │  Media store (ciphertext) │
│  Media (encrypted)       │          │  Discovery (@username)    │
│  Calls (WebRTC + E2EE)   │          │  Push (content-free)      │
└──────────────────────────┘          │  Bot Gateway             │
                                      └──────────┬────────────────┘
                                                   │
                                      PostgreSQL / Redis / S3
                                                   │
                                      bots/enchat-bridge ──► ntfy / Enchat
```

Ключевой принцип: **сервер — слепой ретранслятор**. Вся чувствительная логика
(шифрование, расшифровка, проверка файлов, поиск по сообщениям) выполняется на клиенте.

## 2. Android-клиент

Единый Gradle-проект, модульная структура:

```
android/
  app/                 точка входа, навигация, DI
  core/
    common/            Result, диспетчеры, утилиты
    crypto/            libsignal, libsodium, Keystore, KDF
    database/          Room + SQLCipher, DAO, миграции
    datastore/         зашифрованные настройки
    network/           Ktor/OkHttp, WebSocket, pinning
    model/             доменные модели
    ui/                дизайн-система, темы, компоненты
    media/             шифрование вложений, превью
    antivirus/         YARA, сигнатуры, эвристики
    calls/             WebRTC + FrameCryptor
  feature/
    onboarding/ auth/ chats/ chat/ contacts/ profile/
    groups/ channels/ stickers/ settings/ calls/
```

Слои: `UI (Compose) → ViewModel → UseCase → Repository → (Network | DB | Crypto)`.
Зависимости направлены внутрь, доменный слой не знает о Android.

### Локальное хранилище
- Room поверх SQLCipher (AES-256).
- Ключ БД генерируется случайно и хранится в Android Keystore.
- Вложения шифруются отдельными ключами (XChaCha20-Poly1305), ключи — в зашифрованной БД.
- Настройки — EncryptedSharedPreferences / DataStore с ключом из Keystore.

### Фоновая работа
- FCM — только «wake-up» без текста (`type`, `envelope_id`).
- WorkManager — докачка медиа, отправка очереди, ротация prekeys.
- Foreground service — только на время звонка.

## 3. Сервер

Kotlin/Ktor, PostgreSQL, Redis, S3-совместимое хранилище.

| Компонент | Ответственность |
|---|---|
| `auth` | регистрация без телефона, Argon2id-verifier, device hash, TOTP 2FA |
| `directory` | публичные ключи, prekeys, `@username` |
| `mailbox` | приём/выдача зашифрованных конвертов, ACK, TTL |
| `gateway` | WebSocket-подписка на новые конверты |
| `media` | приём/выдача шифрованных блобов, квоты |
| `push` | FCM/APNs без содержимого |
| `moderation` | жалобы (только метаданные, контент недоступен) |
| `bots` | Bot API, webhook, long polling, scopes, rate limits |

### Модель данных (упрощённо)

```sql
users(id, username, password_verifier, device_hash, totp_secret, created_at)
devices(id, user_id, identity_key, signed_prekey, prekey_sig, last_seen)
one_time_prekeys(device_id, key_id, public_key)
envelopes(id, recipient_token, ciphertext, size, created_at, expires_at, delivered_at)
blobs(id, owner_token, size, sha256, created_at, expires_at)
channels(id, owner_id, public_key, title, username)
```

`recipient_token` — это не user_id, а ротируемый blinded-токен, выданный сервером
устройству при регистрации и обновляемый раз в N дней. Связь токена с пользователем
хранится только на устройстве.

## 4. Поток сообщения 1:1

1. Отправитель строит сессию X3DH (или использует существующую).
2. Шифрует `MessageProto` через Double Ratchet.
3. Оборачивает в `SealedEnvelope` (без открытого ID отправителя).
4. Паддинг до фиксированного класса размера, шифрование получателю.
5. `POST /v1/envelope` → сервер кладёт в mailbox по `recipient_token`.
6. Получатель забирает конверт (WS-push или polling), дешифрует локально.
7. ACK удаляет конверт; недоставленные живут до TTL.

## 5. Медиа

- Клиент генерирует случайный 256-битный ключ на каждый файл.
- Файл шифруется AES-256-GCM на устройстве; AAD связывает шифротекст с
  SHA-256 и именем файла.
- На сервер уходит только ciphertext; имя, MIME и ключ передаются внутри
  E2EE-сообщения (префикс `TAGA-MEDIA`).
- Relay хранит блобы в файловом каталоге с TTL 7 дней и лимитом 50 МиБ.
- После скачивания клиент проверяет SHA-256 и кеширует файл в приватном
  каталоге, открывая его через FileProvider.
- Превью генерируется и шифруется отдельно (следующий этап).
- Для больших видео планируется потоковое XChaCha20-Poly1305 и чанковая
  докачка.

## 6. Звонки

- Сигнализация (offer/answer/ICE) идёт через E2EE-канал сообщений.
- Медиа — WebRTC (DTLS-SRTP), плюс FrameCryptor с ключом, выведенным из сессии
  Double Ratchet, чтобы SFU не имел доступа к медиа.
- TURN-сервер видит только IP и объём трафика, но не содержимое.

## 7. Боты

Бот — отдельная доверенная конечная точка. Сообщения боту расшифровываются на клиенте и
передаются в Bot Gateway по TLS; ключи E2EE боту недоступны. Privacy mode по умолчанию:
бот видит только команды, упоминания и ответы на свои сообщения.

Сервер 1.2.0: отдельный `BotStore` (`bots.json`, `bot-updates.jsonl`,
`bot-chats.jsonl`), токены HMAC + AES-256-GCM, Bot API v1 (`/bot/v1`) и
пользовательский `/v1/bots`, встроенный `@BotFather`. Файлы, webhook, Web App,
inline и боты в комнатах — `[~]`. Контракты: [bot-api.md](bot-api.md),
безопасность: [bot-security.md](bot-security.md). Подробности —
[docs/bots.md](bots.md).

Первый бот — **Enchat Bridge** — отдельный сервис (`bots/enchat-bridge`), который
ретранслирует сообщения в публичные комнаты Enchat через ntfy. Мост видит plaintext,
поэтому включается только по явному согласию и не входит в APK. См.
[docs/enchat-bridge.md](enchat-bridge.md).

## 8. Отказоустойчивость

- При недоступности сервера клиент показывает плашку с контактом владельца
  (<https://vk.ru/rf_dron>) и продолжает работать с локальным кешем.
- Очередь исходящих хранится локально и отправляется при восстановлении связи.
