# Deployment

## Текущий сервер

- Адрес: `https://83-141-92-137.nip.io` (nip.io → `83.141.92.137`).
- Ubuntu 26.04, 1 vCPU, 1.9 GiB RAM.
- Relay: systemd `tagasay.service`, порт `127.0.0.1:8080`.
- TLS: Caddy + Let's Encrypt, автоматическое продление.
- Порт 22 — SSH; 80/443 — Caddy.

Проверка:

```bash
curl https://83-141-92-137.nip.io/health
```

## Обновление

```bash
cd server
./gradlew installDist
tar -czf tagasay-server.tar.gz -C build/install tagasay-server
scp tagasay-server.tar.gz root@83.141.92.137:/tmp/
ssh root@83.141.92.137 '
  systemctl stop tagasay
  # Не удаляйте /opt/tagasay/data — там аккаунты и очереди.
  tar -xzf /tmp/tagasay-server.tar.gz -C /opt/tagasay --strip-components=1
  chmod +x /opt/tagasay/bin/tagasay-server
  chown -R tagasay:tagasay /opt/tagasay
  systemctl start tagasay
'
```

`/etc/systemd/system/tagasay.service`:

```ini
[Service]
User=tagasay
WorkingDirectory=/opt/tagasay
Environment=JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
Environment=TAGASAY_PORT=8080
Environment=TAGASAY_DATA=/opt/tagasay/data/store.json
Environment=TAGASAY_MEDIA=/opt/tagasay/data/media
Environment=TAGASAY_BOT_DATA=/opt/tagasay/data/bots
Environment=TAGASAY_ENV=production
EnvironmentFile=/etc/tagasay/bot.env
ExecStart=/opt/tagasay/bin/tagasay-server
Restart=always
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ReadWritePaths=/opt/tagasay
```

Хранилище — `InMemoryStore` с JSON-снапшотом в `/opt/tagasay/data/store.json`
(переживает перезапуск сервиса; права `600`) и каталог шифрованных вложений
`/opt/tagasay/data/media` (права `700`). Следующий шаг — PostgreSQL и Redis.

Боты живут отдельно в `/opt/tagasay/data/bots` (`bots.json`,
`bot-updates.jsonl`, `bot-chats.jsonl`, права `600`) и **не** переписывают
пользовательский снапшот. Мастер-ключ токенов — `/etc/tagasay/bot.env`
(`TAGASAY_BOT_TOKEN_KEY=...`, права `600`, не в git/бэкапе репозитория):
`TAGASAY_ENV=production` без ключа останавливает старт сервера (fail-fast).

Обновление не должно удалять `/opt/tagasay/data`: распаковывайте архив поверх,
затем выполните `mkdir -p /opt/tagasay/data/media && chown -R tagasay:tagasay
/opt/tagasay && chmod 700 /opt/tagasay/data/media`.

## App

- Debug: `http://127.0.0.1:8080` + `adb reverse tcp:8080 tcp:8080`.
- Release: `https://83-141-92-137.nip.io`.

## Production hardening в 1.0.0

- Rate limits: auth 10–20/мин, envelope 120–240/мин, media 20–120/мин,
  directory 60–120/мин (ключ — `X-Forwarded-For` от Caddy или прямой IP).
- Логи relay — только ошибки, без путей, `@username` и id медиа.
- Mailbox: до 1000 конвертов на устройство, TTL 30 дней, просроченные
  очищаются при записи и чтении.
- Медиа: 50 МиБ, TTL 7 дней, права файлов `600`, каталогов `700`.
- Боты: токены HMAC + AES-GCM под `TAGASAY_BOT_TOKEN_KEY`; в логи не попадают
  текст, токены, `@username`; updates 10 000/7 дней, бот-чаты 30 дней,
  лимит 20 ботов/владельца, 30 msg/s на бота и 1 msg/s в чат.
- Caddy обязателен: relay слушает `127.0.0.1:8080`, наружу — только TLS.
- Перед постоянной эксплуатацией: PostgreSQL/Redis, бэкапы с restore-тестом,
  мониторинг `/health` и 5xx, fail2ban, geo-IP на сервере.
- Полный чек-лист: [release.md](release.md).
