# Release 1.0.0

Чек-лист выпуска и развёртывания для закрытого контура.

## 1. Версия и тег

- `android/app/build.gradle.kts`: `versionCode` и `versionName`.
- `server/src/main/kotlin/com/tagasay/server/BuildInfo.kt`: `VERSION`.
- `CHANGELOG.md`: секция релиза.
- Тег и пуш:

```bash
git tag -a v1.0.0 -m "TAGa-Say 1.0.0"
git push origin v1.0.0
```

Тег запускает `.github/workflows/release.yml`: unit-тесты, release APK/AAB,
серверный дистрибутив и GitHub Release. Если workflow ещё не активирован,
скопируйте его из `ci/github-actions/release.yml` в `.github/workflows/`
(см. `ci/README.md` — нужен PAT со scope `workflow`).

## 2. Подпись

Секреты репозитория (Settings → Secrets and variables → Actions):

| Секрет | Значение |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 android/keystore/tagasay-release.jks` |
| `ANDROID_STORE_PASSWORD` | пароль хранилища |
| `ANDROID_KEY_ALIAS` | `tagasay` |
| `ANDROID_KEY_PASSWORD` | пароль ключа |

Без секретов workflow соберёт **неподписанный** APK — он не устанавливается
на устройство. Для публикации подписывайте локально:

```bash
cd android
./gradlew :app:assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Ключ и `keystore.properties` не коммитятся (см. `.gitignore`). Храните их
в офлайн-сейфе; потеря ключа делает обновления невозможными.

## 3. Проверка перед выпуском

- [ ] `./gradlew :core:crypto:testDebugUnitTest` — зелёный.
- [ ] `cd server && ./gradlew test` — зелёный.
- [ ] Debug/release APK собираются, R8 не ломает сериализацию.
- [ ] Установка на чистое устройство: онбординг, регистрация, 2FA.
- [ ] Два устройства: текст, вложение, edit/delete, delivered/read.
- [ ] Фон: свернуть приложение, отправить сообщение, проверить уведомление.
- [ ] Фон: нажать уведомление — открывается нужный чат.
- [ ] Перезапуск приложения: сессия и история сохраняются.
- [ ] `FLAG_SECURE`: скриншот приложения пустой.
- [ ] Release: вне России / с VPN показывается блокирующий экран.
- [ ] Panic-wipe: vault и ключи уничтожаются, приложение возвращается к онбордингу.
- [ ] Сервер перезапускается, `store.json` и `media/index.json` на месте.
- [ ] `/health` отвечает, TLS-сертификат валиден.

## 4. Эксплуатация сервера

Минимум для постоянной работы:

- PostgreSQL + Redis вместо JSON-снапшота (следующий этап; сейчас снапшот
  переживает рестарт, но не масштабируется и не даёт транзакций).
- Ежедневные зашифрованные бэкапы `/opt/tagasay/data` с проверкой restore.
- systemd `Restart=always`, `ProtectSystem=strict`, отдельный пользователь.
- Caddy: HSTS, TLS 1.3, `X-Forwarded-For` только от доверенного прокси.
- Firewall: открыты только 22/80/443; SSH по ключу, fail2ban.
- Мониторинг `/health`, диска, памяти и ошибок 5xx; алерты.
- Ротация логов; логи relay не содержат текста и путей.

### AEZA monitoring

Ключ API и параметры хостинга — только на сервере, в
`/etc/tagasay/aeza.env` (права `600`, владелец `root`):

```ini
AEZA_API_KEY=<ключ из ЛК AEZA>
TAGASAY_PUBLIC_IP=83.141.92.137
TAGASAY_DONATE_URL=https://<ссылка на оплату: СБП/карта/страница AEZA>
```

В unit добавьте `EnvironmentFile=/etc/tagasay/aeza.env`. Relay проксирует
`/v1/server/status` (кэш 10 минут) и отдаёт приложению:

- `paidUntil` — дата оплаты сервера;
- `balance` — точный баланс ЛК (AEZA возвращает центы: `138` → `1.38`);
- `price`/`paymentTerm` — стоимость сервера (`593` → `5.93 EUR/мес`);
- `shortfall` — сколько не хватает до продления;
- `deletionAt` — оценка удаления (7 дней после оплаты);
- `projectServers` — число серверов проекта;
- `donateUrl` — ссылка для кнопки «Пополнить хостинг».

`TAGASAY_DONATE_URL` по умолчанию указывает на VK владельца. Чтобы
пользователи могли **сами** пополнить хостинг, укажите прямую ссылку на
оплату (СБП/карта) — приложение откроет её по кнопке.

- `TAGASAY_DATA` и `TAGASAY_MEDIA` на отдельном разделе с правами `600/700`.

## 5. Юридические и организационные требования

- Внешний аудит криптографии и пентест **до** работы с реальными данными.
- Политика конфиденциальности и согласие пользователей (см. `PRIVACY.md`).
- Лицензия Enchat Bridge: письменное разрешение правообладателя до публикации.
- Для гос/спец. контура: согласование с ИБ-службой, изолированный relay,
  собственный домен и сертификат, запрет Google-сервисов на устройствах.
- Не публиковать `prompt.txt`, ключи, `.env`, `keystore.properties`.

## 6. Известные ограничения 1.0.0

- Группы, каналы, звонки — UI-каркас без серверной логики.
- Нет мультиустройства и переноса истории.
- Sealed sender и WebSocket-доставка не реализованы (long-poll).
- Push-уведомления без Google: foreground-service, расход батареи выше FCM.
- Нет внешнего аудита; проект нельзя считать готовым для гостайны без него.
