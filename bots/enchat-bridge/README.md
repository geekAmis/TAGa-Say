# Enchat Bridge

Первый официальный бот TAGa-Say: мост в публичные комнаты
[Enchat](https://github.com/sudodevdante/enchat).

Мост — **отдельный opt-in сервис**. Он не входит в APK, не вендорит код Enchat и
работает только с сетевыми форматами Enchat/ntfy.

> ⚠️ Мост видит сообщения в открытом виде: TAGa-Say расшифровывает их для бота, а затем
> мост шифрует их ключом комнаты Enchat. Сквозного шифрования *через* мост нет.
> Включайте только для публичных комнат и не пересылайте секреты.

> ⚠️ Лицензия Enchat (© sudodevdante) запрещает redistribution/modification без
> письменного разрешения. Перед публикацией моста получите разрешение автора.

## Возможности

- `/connect <название>` — подключить чат к публичной комнате из каталога.
- `/connect topic:secret` — подключить напрямую по credentials.
- `/connect-link <url>` — подключить по invite-ссылке Enchat.
- `/disconnect`, `/status`, `/rooms`, `/nick`, `/bridge on|off`, `/security`.
- Ретрансляция в обе стороны, анти-эхо, лимит 500 символов, backoff при 429/5xx.
- Публичный каталог — только если заданы `ENCHAT_DIRECTORY_TOPIC` и
  `ENCHAT_DIRECTORY_KEY` (не хардкодим чужие константы).

## Запуск

```bash
cd bots/enchat-bridge
python -m venv .venv
. .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt
cp .env.example .env        # заполните токен и relay
set -a; . ./.env; set +a
python -m enchat_bridge.main
```

Переменные окружения:

| Переменная | Обязательно | Описание |
|---|---|---|
| `TAGASAY_API` | да | `https://<server>/bot/v1` |
| `TAGASAY_BOT_TOKEN` | да | токен бота из BotFather |
| `ENCHAT_RELAY` | да | `https://enchat.salvc.com` или свой ntfy |
| `ENCHAT_NICK` | да | ник моста в комнатах |
| `ENCHAT_DIRECTORY_TOPIC` | нет | топик каталога публичных комнат |
| `ENCHAT_DIRECTORY_KEY` | нет | Fernet-ключ каталога (base64url) |
| `TOR_PROXY` | нет | `socks5h://127.0.0.1:9050` |

## Тесты

```bash
python -m pytest -q
```

## Ограничения alpha

- Только текст (файлы — следующий шаг).
- Состояние в памяти: после рестарта подключения нужно восстановить.
- Нет постоянного audit log (содержимое не логируется намеренно).
- Нет проверки личности в Enchat: ключ комнаты = доступ.
