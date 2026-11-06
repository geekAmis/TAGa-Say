# Криптография

> Мы не реализуем примитивы самостоятельно. Используются проверенные библиотеки:
> **libsignal** (X3DH, Double Ratchet, Sender Keys) и **libsodium** (XChaCha20-Poly1305,
> Argon2id, Ed25519, X25519).

## 1. Иерархия ключей

```
Master Secret (256 бит, генерируется в Keystore/StrongBox)
│
├─ Local DB Key        = HKDF(master, "taga/db/v1")
├─ Local Media Key     = HKDF(master, "taga/media/v1")
├─ Backup Key          = HKDF(Argon2id(passphrase, salt), "taga/backup/v1")
│
└─ Identity Key Pair   (Ed25519 / X25519, libsignal)
   ├─ Signed PreKey
   ├─ One-Time PreKeys
   └─ Sessions (Double Ratchet)
```

`master` никогда не покидает защищённое хранилище в открытом виде.
Экспорт возможен только в зашифрованном виде (backup) с Argon2id.

### 1.1. Привязка к устройству и серверу
По требованию проекта:

```
ikm        = device_secret (случайные 32 байта из Keystore)
salt       = server_salt (публичная константа сервера, 32 байта)
info       = "taga/master/v1" || user_passphrase_hash
master     = HKDF-SHA256(ikm, salt, info, 32)
```

`device_secret` — единственный секретный вход. `server_salt` обеспечивает domain
separation между серверами/средами (prod/staging), но не является секретом.
Парольная фраза (если задана) дополнительно усиливается Argon2id:
`m=64 MiB, t=3, p=1`.

## 2. Регистрация и сессии

1. Клиент генерирует identity key, signed prekey и N one-time prekeys.
2. Публичные части загружаются на сервер; приватные остаются на устройстве.
3. Сервер выдаёт `device_token` и `delivery_token` (blinded).
4. Аутентификация запросов — подпись Ed25519 над `method || path || body_hash || nonce || ts`.
5. Сессия — короткоживущий bearer + refresh, привязанный к ключу устройства.

## 3. Личные чаты (X3DH + Double Ratchet)

- Инициатор: X3DH с identity key, signed prekey и one-time prekey получателя.
- Далее — Double Ratchet (DH-ratchet + симметричная цепочка).
- Заголовок: `ratchet_key, counter, previous_counter`.
- AEAD: XChaCha20-Poly1305 (или AES-256-GCM через libsignal).
- Пропущенные сообщения: хранение до `MAX_SKIP = 1000`.
- Смена ключей при каждом сообщении/ответе (DH ratchet).

## 4. Группы (Sender Keys)

- Каждый участник рассылает свой Sender Key участникам через pairwise-сессии.
- Групповое сообщение шифруется один раз Sender Key (AES-256-CBC + HMAC или
  XChaCha20-Poly1305 в зависимости от версии libsignal).
- Ротация при выходе участника.
- Для больших групп — Sender Keys + подпись отправителя.

## 5. Вложения

```
file_key      = random(32)
chunk_nonce   = LE64(counter)
chunk_ct      = XChaCha20-Poly1305(file_key, chunk_nonce, chunk, aad=file_id||counter)
blob_id       = SHA-256(ciphertext)   // content-addressed
```

- Ключ и MIME/имя/размер передаются внутри E2EE-сообщения.
- Превью — отдельный маленький блоб с отдельным ключом.
- Лимиты: 2 ГиБ на файл (настраивается), проверка свободного места.

## 6. Звонки

- Сигнализация — через E2EE-сообщения (`CallOffer`, `CallAnswer`, `IceCandidate`).
- Медиа — DTLS-SRTP (WebRTC).
- Дополнительно: FrameCryptor шифрует каждый кадр ключом, выведенным из ratchet-сессии:
  `call_key = HKDF(ratchet_root, "taga/call/v1/" || call_id)`.
- SFU пересылает только шифрованные кадры и не имеет `call_key`.

## 7. Локальное хранилище

- SQLCipher (AES-256-CBC) с ключом из Keystore.
- Вложения: XChaCha20-Poly1305, ключ на файл.
- Ключи не пишутся в логи, не попадают в backup ОС (`allowBackup=false`).
- `FLAG_SECURE` на экранах чата, блокировка по таймауту, опциональный panic-wipe.

## 8. Верификация собеседника

- Отпечаток identity key: `SHA-256(pubkey)` в виде 60 цифр / QR.
- Сравнение вручную или сканированием QR.
- Предупреждение при смене ключа (key change).
- TOFU по умолчанию, с явной отметкой «не верифицирован».

## 9. Обновление ключей

- One-time prekeys пополняются при < 10 оставшихся.
- Signed prekey ротируется раз в 30 дней.
- Delivery-токены ротируются раз в 14 дней.
- Сессии сбрасываются по запросу пользователя («сбросить шифрование»).

## 10. Тестирование

- Векторные тесты для KDF и сериализации.
- Property-based тесты ratchet (порядок, пропуски, replay).
- Тесты на невозможность расшифровки без ключа.
- Fuzzing парсеров конвертов и вложений.
