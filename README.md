# TAGa-Say

**Приватный мессенджер с оконечным шифрованием для Android.**

TAGa-Say — закрытая система обмена сообщениями, в которой сервер не видит ни содержимое
переписки, ни то, кто с кем общается. Шифрование выполняется на устройстве, ключи никогда
не покидают телефон в открытом виде, а сервер выступает только как «слепой» ретранслятор
зашифрованных конвертов.

> Статус: release candidate **1.2.0** для закрытого контура. Работает: Compose-UI,
> зашифрованный локальный vault, сервер-ретранслятор, **E2EE-текст через libsignal
> (X3DH + Double Ratchet + PQXDH)**, **шифрованные вложения**, **sealed sender**
> (relay не хранит отправителя), редактирование, удаление, пересылка и ответы,
> квитанции доставки/прочтения, паддинг конвертов, **фоновый приём и уведомления
> без Google/FCM**, клиентский антивирус, **автообновление с mandatory-режимом**,
> **папки чатов, истории, поиск, опросы, реакции и журнал звонков**,
> Bot API и прототип Enchat Bridge.
> Сервер развёрнут: `https://83-141-92-137.nip.io` (TLS, systemd, JSON-снапшот).
> Не включено в 1.0: группы, каналы, звонки, мультиустройство.
> Перед работой с реальными чувствительными данными требуется внешний аудит —
> см. [docs/release.md](docs/release.md).
> Сборки: [GitHub Releases](https://github.com/geekAmis/TAGa-Say/releases/latest).

---

## Возможности (целевые)

- Личные чаты, группы, каналы, секретные чаты с самоуничтожающимися сообщениями.
- Отправка фото, видео, GIF, документов любого формата, голосовых сообщений.
- Редактирование, удаление, пересылка сообщений между чатами.
- Стикеры и стикер-паки, реакции, ответы, закреплённые сообщения.
- Аудио- и видеозвонки (WebRTC + дополнительное оконечное шифрование).
- Аккаунт без привязки к телефону: `@username`, пароль и уникальный скрытый
  device hash. Телефон не запрашивается и не хранится.
- 2FA Google Authenticator (TOTP) в один клик из профиля.
- Поиск людей по `@username`, по ссылке `https://<server>/@username`,
  по deep link `taga://open/@username`.
- Release-сборка работает только в России и блокируется при активном VPN.
- Встроенная проверка файлов (антивирус): сигнатуры, эвристики, проверка архивов,
  репутация по хешу — без отправки содержимого файлов на сервер.
- Платформа ботов: Bot API, webhook/long polling, inline-кнопки, каталог, scopes.
- Первый официальный бот — **Enchat Bridge**: мост в публичные комнаты Enchat
  (opt-in, отдельный сервис).
- Плашка «Сервер недоступен» с контактом владельца проекта.

## Безопасность

| Компонент | Решение |
|---|---|
| Транспорт | TLS 1.3 + pinning в release (в debug — локальный relay) |
| Парный протокол | X3DH + Double Ratchet + PQXDH (libsignal) |
| Групповой протокол | Sender Keys (libsignal) — в разработке |
| Симметричное шифрование | AES-256-GCM поверх libsignal, XChaCha20-Poly1305 в core |
| Ключи | Android Keystore, Argon2id для парольной фразы |
| Аккаунт | @username + Argon2id-verifier + device hash, TOTP 2FA |
| Локальная БД | AES-256-GCM vault + Keystore (SQLCipher — следующий шаг) |
| Метаданные | Sealed sender, blinded delivery tokens, паддинг конвертов |
| Звонки | DTLS-SRTP + FrameCryptor (E2EE поверх WebRTC) — в разработке |

Подробнее: [docs/threat-model.md](docs/threat-model.md), [docs/crypto.md](docs/crypto.md).

## Структура репозитория

```
android/          Android-клиент (Kotlin, Jetpack Compose)
server/           Сервер-ретранслятор (Kotlin/Ktor, PostgreSQL, Redis)
bots/             Сервисы ботов (Enchat Bridge)
docs/             Архитектура, модель угроз, криптопротокол, Bot API
.github/          CI, шаблоны issue
```

## Сборка Android-клиента

Требования: JDK 17, Android SDK 35, Android Studio Koala+ или Gradle CLI.

```bash
cd android
./gradlew assembleDebug
```

Файл `local.properties` (путь к SDK) и ключи подписи в репозиторий не попадают.

## Сервер

Сервер не хранит открытый текст. Он принимает и выдаёт зашифрованные конверты,
хранит публичные ключи и очереди доставки.

См. [server/README.md](server/README.md) (появится на этапе 2 роадмапа).

## Документация

- [Архитектура](docs/architecture.md)
- [Модель угроз](docs/threat-model.md)
- [Криптография](docs/crypto.md)
- [Протокол](docs/protocol.md)
- [Bot API](docs/bots.md)
- [Enchat Bridge](docs/enchat-bridge.md)
- [Разбор референсов](docs/reference-notes.md)
- [Deployment](docs/deployment.md)
- [Обновления](docs/updates.md)
- [Синхронизация состояния](docs/sync.md)
- [Release и чек-лист](docs/release.md)
- [Changelog](CHANGELOG.md)

## Лицензия

MIT — см. [LICENSE](LICENSE). Сторонние интеграции сохраняют свои лицензии:
Enchat © sudodevdante (internal use only), ZAP — MIT.

## Политика конфиденциальности

[PRIVACY.md](PRIVACY.md).

## Связь с владельцем проекта

При недоступности сервера приложение показывает пользователю контакт:
<https://vk.ru/rf_dron>.
