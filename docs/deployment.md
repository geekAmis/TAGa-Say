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
ExecStart=/opt/tagasay/bin/tagasay-server
Restart=always
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ReadOnlyPaths=/opt/tagasay
```

Хранилище — `InMemoryStore` с JSON-снапшотом в `/opt/tagasay/data/store.json`
(переживает перезапуск сервиса; права `600`). Следующий шаг — PostgreSQL и Redis.

## App

- Debug: `http://127.0.0.1:8080` + `adb reverse tcp:8080 tcp:8080`.
- Release: `https://83-141-92-137.nip.io`.
