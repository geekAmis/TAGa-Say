# Changelog

Все заметные изменения проекта. Формат — [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/),
версии — [Semantic Versioning](https://semver.org/lang/ru/).

## [1.0.0] — 2026-11-07

Первый релиз-кандидат для закрытого развёртывания.

### Добавлено
- E2EE 1:1 на libsignal: X3DH + Double Ratchet + PQXDH (Kyber-1024).
- Шифрованные вложения: AES-256-GCM на устройстве, ciphertext-блобы на relay,
  ключ и метаданные — только внутри E2EE-конверта.
- Единый `WireMessage` (текст, вложения, редактирование, удаление,
  пересылка, ответы) и квитанции `delivered`/`read`.
- Паддинг открытого текста до классов размера (`TGF1` + length-prefix).
- Фоновый приём сообщений через foreground-service (без Google/FCM) и
  уведомления без текста сообщения.
- AEZA-мониторинг в настройках: оплачен до, баланс ЛК, стоимость сервера,
  дефицит до продления и кнопка «Донат на сервер».
- Онбординг без телефона: `@username` + пароль (Argon2id) + device hash.
- 2FA TOTP (Google Authenticator) и вход на новом устройстве.
- Клиентский антивирус: magic bytes, PE/ELF/Mach-O/APK, макросы,
  двойные расширения, zip-bomb / path traversal в архивах.
- Защита от скриншотов (`FLAG_SECURE`), panic-wipe, шифрованный vault.
- Региональное ограничение release-сборки (Россия, блокировка VPN).
- Deep links `taga://open/@username`, поиск по `@username`.
- Enchat Bridge (отдельный opt-in сервис, не входит в APK).

### Безопасность
- Rate limiting на auth/envelope/directory/media.
- Ограничение размера mailbox и TTL конвертов; очистка просроченных.
- Логи relay не содержат путей, `@username` и id медиа.
- MIME/sha256-проверка ciphertext-блобов, права `600`/`700` на данные.
- Токены и ключи не покидают устройство в открытом виде.

### Известные ограничения
- Группы, каналы и звонки пока не включены в релиз (UI-каркас есть).
- Нет мультиустройства и восстановления истории на новом устройстве.
- Sealed sender и WebSocket-доставка не реализованы (long-poll).
- Внешний аудит криптографии ещё не проводился.

[1.0.0]: https://github.com/geekAmis/TAGa-Say/releases/tag/v1.0.0
