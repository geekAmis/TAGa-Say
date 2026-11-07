# Автообновление

## Как работает

1. Приложение при старте и раз в 6 часов запрашивает `GET <relay>/v1/version`
   (публичный endpoint, без авторизации).
2. Relay отвечает:

```json
{
  "versionCode": 5,
  "versionName": "1.0.3",
  "mandatory": false,
  "minSupportedVersionCode": 4,
  "sha256": "3077e140…",
  "downloadUrl": "https://github.com/geekAmis/TAGa-Say/releases/download/v1.0.3/TAGa-Say-1.0.3.apk",
  "mirrorUrl": "https://…/v1/version/apk",
  "notes": "…",
  "available": true
}
```

3. Если `versionCode > BuildConfig.VERSION_CODE`:
   - `mandatory == true` или `VERSION_CODE < minSupportedVersionCode` → **блокирующий
     экран**, приложение не открывает чаты;
   - иначе — **скрываемая плашка** «Доступно обновление».
4. APK скачивается с `downloadUrl` (GitHub Release) или `mirrorUrl` (relay).
5. Проверяются `sha256` из ответа и подпись release-сертификата
   (`RELEASE_CERT_SHA256` в сборке).
6. Запускается системный установщик. Android требует подтверждения установки;
   приложение не может поставить APK полностью без участия пользователя.
7. После установки приложение перезапускается. Если версия всё ещё старая,
   блокирующий экран остаётся.

## Политика на сервере

`version.json` в `TAGASAY_UPDATE_DIR` (по умолчанию `/opt/tagasay/updates`):

```json
{
  "versionCode": 5,
  "versionName": "1.0.3",
  "mandatory": false,
  "minSupportedVersionCode": 4,
  "sha256": "<sha256 APK>",
  "downloadUrl": "https://github.com/geekAmis/TAGa-Say/releases/download/v1.0.3/TAGa-Say-1.0.3.apk",
  "notes": "…"
}
```

- `TAGASAY_PUBLIC_URL` — публичный URL relay. Если в `TAGASAY_UPDATE_DIR` лежит
  `latest.apk`, сервер отдаёт его как `mirrorUrl` (`/v1/version/apk`) и сам
  считает SHA-256.
- Без `version.json` endpoint возвращает `available:false`.
- `sha256` обязателен: клиент отказывается устанавливать APK без совпадения.

## Публикация обновления

1. Поднять `versionCode`/`versionName` в `android/app/build.gradle.kts`.
2. Собрать и подписать `:app:assembleRelease`.
3. Создать GitHub Release с APK; взять URL ассета.
4. Вычислить хеш:

```powershell
Get-FileHash app-release.apk -Algorithm SHA256
```

5. Записать `version.json` на relay (`TAGASAY_UPDATE_DIR`). При необходимости
   положить `latest.apk` рядом для зеркала.
6. `mandatory: true` — только если старые версии несовместимы или небезопасны.

## Безопасность

- Принимаются только HTTPS-ссылки; `http://` игнорируется.
- SHA-256 из ответа сервера обязателен.
- Подпись APK сверяется с `RELEASE_CERT_SHA256` (release-сертификат проекта).
- Кэш mandatory хранится 30 дней и блокирует приложение даже офлайн.
- GitHub Release публичный; сервер отдаёт только хэш и метаданные, API-ключи
  для обновления не нужны.
