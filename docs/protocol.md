# Протокол

Базовый URL: `https://<host>/v1`. Транспорт: HTTP/2 + WebSocket.
Все тела — protobuf (в отладочных сборках допустим JSON).

## 1. Аутентификация

```
POST /v1/auth/challenge   { device_pubkey }
→ { challenge, expires_at }

POST /v1/auth/register    { username, password_verifier, device_hash,
                            identity_key, signing_key, signed_prekey,
                            prekey_sig, kyber_prekeys, challenge_sig }
→ { device_token, delivery_token, server_salt, next_prekey_id }

POST /v1/auth/refresh     (device_token)
→ { access_token, expires_at }
```

Каждый запрос подписывается Ed25519:
`sig = Sign(identity_key, method || path || sha256(body) || nonce || timestamp)`.
Сервер отвергает `|now - timestamp| > 300s` и повторные nonce.

## 2. Конверты

`SealedEnvelope`:

```protobuf
message SealedEnvelope {
  bytes  id            = 1;  // random 128-bit
  bytes  recipient     = 2;  // blinded delivery token
  bytes  ciphertext    = 3;  // Double Ratchet output
  uint32 size_class    = 4;  // 0..7, фиксированные классы размера
  uint64 created_at    = 5;
  uint64 expires_at    = 6;
  bytes  sender_hint   = 7;  // опционально, зашифрован внутри
  bytes  ack_token     = 8;  // для подтверждения доставки
}
```

- `recipient` не содержит user_id.
- `ciphertext` — вывод Double Ratchet над кадрированным `WireMessage` (см. ниже).
- Перед шифрованием открытый текст получает заголовок `"TGF1"` + 4-байтную длину
  и padding до класса: 256 B, 1 KiB, 4 KiB, 16 KiB, 64 KiB, 256 KiB, 1 MiB.
  Relay видит только класс, а не точную длину. Старый сырой текст без заголовка
  принимается как есть (обратная совместимость).

### MessageProto (WireMessage)

Внутри E2EE-конверта лежит JSON с бинарным префиксом `TAGA-WIRE\u0001`:

```json
{
  "v": 1,
  "type": "text | media | edit | delete | delivered | read",
  "id": "UUID сообщения (стабилен у отправителя и получателя)",
  "timestamp": 1762473600000,
  "text": "текст или подпись",
  "kind": "TEXT | IMAGE | VIDEO | FILE | AUDIO | STICKER",
  "sticker": null,
  "replyToId": "UUID цитируемого сообщения",
  "replyToText": "краткая цитата",
  "forwardedFrom": "название исходного чата",
  "attachment": {
    "kind": "IMAGE",
    "name": "photo.jpg",
    "mimeType": "image/jpeg",
    "sizeBytes": 123456,
    "sha256": "hex",
    "scanVerdict": "SAFE | WARNING | DANGEROUS",
    "mediaId": "capability-id блоба",
    "keyBase64": "AES-256 ключ файла"
  },
  "ids": ["UUID", "..."]
}
```

- `text`/`media` — новые сообщения; получатель подтверждает их конвертом
  `type=delivered` с теми же `ids`.
- `edit`/`delete` ссылаются на `id`; применяются только к сообщениям собеседника.
- `read` отправляется при открытии чата (если включены read receipts) и содержит
  id входящих сообщений; отправитель переводит их в `READ`.
- Квитанции идемпотентны: повторный `delivered` не понижает `READ`.

### API

```
POST /v1/envelope              отправить конверт
GET  /v1/envelope?limit=100    забрать пачку (long-poll)
POST /v1/envelope/ack          подтвердить доставку ({"ids":[...]})
```

Сервер не хранит отправителя конверта. Для ответа используется ratchet-сессия.

## 3. WebSocket

```
wss://<host>/v1/ws?token=...
```

Клиент → сервер:
```json
{"type":"subscribe","token":"<delivery_token>"}
{"type":"ack","ids":["..."]}
{"type":"ping","ts":123}
```

Сервер → клиент:
```json
{"type":"envelope","id":"...","size":1024}
{"type":"presence","..."}
{"type":"pong","ts":123}
```

Содержимое конверта по WebSocket не передаётся — только уведомление о наличии.

## 4. Медиа

Реализовано в alpha:

```
POST   /v1/media          ciphertext (application/octet-stream)
                          заголовок X-TAGa-Say-Sha256
       → { id, sha256, size, expires_at }

GET    /v1/media/{id}     → ciphertext
DELETE /v1/media/{id}
```

- Клиент шифрует файл AES-256-GCM случайным 256-битным ключом.
- AAD = `taga/media/v1|<sha256>|<original_name>`.
- На сервер уходит только ciphertext; `id` — случайная capability (24 байта).
- Ключ, имя, MIME и SHA-256 передаются внутри E2EE-конверта
  (бинарный префикс `TAGA-MEDIA` + JSON `MediaWire`).
- Лимит — 50 МиБ, TTL — 7 дней, хранилище — файловый каталог relay.
- Получатель проверяет SHA-256 после расшифровки и кеширует файл локально.

Планируется: чанковая загрузка с докачкой, presigned URL и S3-совместимое
хранилище, потоковое XChaCha20-Poly1305 для больших видео.

## 5. Directory / discovery

```
GET  /v1/users/@username          публичный профиль + ключи
POST /v1/auth/2fa/setup            → { secret, otpauth_uri }
POST /v1/auth/2fa/enable          { code } → { enabled }
POST /v1/auth/2fa/disable         { code } → { enabled }
GET  /v1/users/me
PATCH /v1/users/me
```

Deep links:
- `taga://open/@username` — открыть профиль в приложении;
- `https://<host>/@username` — universal/app link, fallback — веб-страница.

## 6. Группы и каналы

```
POST /v1/groups                   создать
POST /v1/groups/{id}/members      добавить (Sender Key рассылается E2EE)
POST /v1/groups/{id}/leave
POST /v1/channels                 создать канал
GET  /v1/channels/{username}
```

Сервер хранит только метаданные (название, аватар-блоб, участники), необходимые
для маршрутизации. Сообщения — E2EE.

## 7. Звонки

Сигнализация — через зашифрованные сообщения:
`CallOffer { call_id, sdp, media_key_commitment }`, `CallAnswer`, `IceCandidate`.
TURN-креденшелы выдаются сервером с TTL 10 минут.

## 8. Ошибки

```json
{"error":{"code":"RATE_LIMITED","message":"...","retry_after":30}}
```

Коды: `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `RATE_LIMITED`, `PAYLOAD_TOO_LARGE`,
`VERSION_UNSUPPORTED`, `SERVER_UNAVAILABLE`.

## 9. Версионирование

Версия протокола — в пути и в AAD сообщения. Несовместимые версии отвергаются,
downgrade запрещён.
