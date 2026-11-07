# Разбор референсных проектов

Дата: 2026-11-07. Изучены `sudodevdante/enchat` и `Amer-alsayed/ZAP`.
Цель — перенять удачные решения, не копируя код и не нарушая лицензии.

## 1. Enchat (Python, терминал)

### Что понравилось
- **Атомарный v2-конверт**: всё необходимое для расшифровки лежит в одном сообщении
  (`V2|ts|nick|content`), нет гонок с доставкой session key.
- **Публичные комнаты с лизом**: запись живёт 105 с и продлевается каждые 75 с; после
  ухода последнего участника комната исчезает из каталога.
- **Zero-knowledge приглашения**: ключ в URL fragment, сервер видит только blob.
- **Tor из коробки** и честный раздел про остаточные метаданные.
- **Простые тесты протокола** без запуска сервера.

### Ограничения, которые мы не повторяем
- Один статический Fernet-ключ на комнату — нет forward secrecy и пост-компрометации.
- Нет аутентификации личности: любой знающий ключ комнаты может выдать себя за другого.
- PBKDF2 480k на общий пароль; у нас Argon2id + Keystore.
- Relay видит topic, время, размер, IP; у нас blinded delivery tokens и паддинг.
- Нет внешнего аудита.

### Что берём
- Идею **атомарного конверта** для bridge-сообщений (один blob = всё для дешифровки).
- **Лиз публичной комнаты** для каталога ботов/каналов.
- **Invite-ссылки с ключом во fragment** для приглашений в группы.
- **Честный блок «Остаточные метаданные»** в документации.

### Лицензия
`internal use only`, запрет redistribution/modification/decompilation. Поэтому:
- код не копируем и не вендорим;
- мост — отдельный opt-in сервис, взаимодействующий по сети;
- константы каталога — только через конфиг, не в репозитории;
- перед публикацией нужно письменное разрешение автора.

## 2. ZAP (React + Node/Socket.IO)

### Что понравилось
- **AAD-контекст**: `{sender, recipient, clientMsgId, timestamp, seq}` входит в AES-GCM,
  что блокирует переадресацию и replay.
- **Deniable authentication**: HMAC от сессии даёт аутентичность без неотказуемости.
- **Safety numbers**: 20-значный отпечаток для out-of-band проверки.
- **Сессии**: короткий access JWT + ротируемый refresh с reuse detection; при сетевых
  ошибках не разлогинивает.
- **Rate limiting по слоям**: auth / salt / session / upload / general.
- **Группы**: версионируемые sealed key envelopes, ротация при изменении состава,
  `joined_kv` для новичков.
- **Offline delivery** и статусы sent/delivered/read.
- **Тесты**: crypto invariants, multi-client integration, Puppeteer E2E.

### Что не копируем
- P-256 ECDH + цепочка `HMAC(root, seq)` — это не Double Ratchet; нет DH-ratchet и
  полноценной пост-компрометации. У нас libsignal.
- Server-side JWT secret и bcrypt-хранилище паролей — у нас Argon2id-verifier пароля,
  device hash и Ed25519-challenge, без телефона.
- Централизованный Socket.IO без sealed sender; у нас конверты без открытого отправителя.
- WebRTC full mesh плохо масштабируется; у нас SFU для групп.

### Что берём в TAGa-Say
1. AAD-привязку `chat_id + message_id + sender + recipient + timestamp` в AEAD.
2. Разделение access/refresh, ротацию refresh и reuse detection.
3. Многоуровневые rate limits, включая отдельный лимит на updates ботов.
4. `joined_kv` / версии ключей для групп и стикер-паков.
5. Safety number в UI проверки ключа.
6. Формат тестов: crypto invariants + multi-client integration + E2E.

## 3. Итог для роадмапа

- Фаза 2: добавить AAD-контекст и safety number (уже есть в docs).
- Фаза 4: группы через Sender Keys + версии ключей, `joined_kv`.
- Новая фаза 4.5: **Bot Platform** и **Enchat Bridge**.
- Фаза 6: reuse detection, rate limits, аудит.
