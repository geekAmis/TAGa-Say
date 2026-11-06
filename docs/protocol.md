# Протокол

Базовый URL: `https://<host>/v1`. Транспорт: HTTP/2 + WebSocket.
Все тела — protobuf (в отладочных сборках допустим JSON).

## 1. Аутентификация

```
POST /v1/auth/challenge   { device_pubkey }
→ { challenge, expires_at }

POST /v1/auth/register    { challenge_sig, phone_proof, identity_key,
                            signed_prekey, prekey_sig, one_time_prekeys[],
                            username?, device_info }
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
- `ciphertext` включает `MessageProto` (тип, текст/медиа-ключ, reply, forward, TTL).
- Паддинг до класса: 256 B, 1 KiB, 4 KiB, 16 KiB, 64 KiB, 256 KiB, 1 MiB, 4 MiB.

### API

```
POST /v1/envelope              отправить конверт
GET  /v1/envelope?limit=100    забрать пачку (long-poll)
POST /v1/envelope/{id}/ack     подтвердить доставку
POST /v1/envelope/{id}/read    отметить прочитанным (опционально)
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

```
POST /v1/blob/init    { size, sha256, chunks }
→ { blob_id, upload_urls[] }

PUT  <upload_url>     ciphertext chunk (presigned, TTL 1 час)
POST /v1/blob/commit  { blob_id }
→ { blob_id, expires_at }

GET  /v1/blob/{id}    → ciphertext
```

Сервер видит только размер и хеш шифротекста. Имя, MIME и ключ — внутри E2EE.

## 5. Directory / discovery

```
GET  /v1/users/@username          публичный профиль + ключи
POST /v1/discovery/phones         { hashes[] } → { matches[] }
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
