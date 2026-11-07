# Сборка

## Android

Требования:
- JDK 17 (Temurin/Microsoft OpenJDK).
- Android SDK Platform 35 + Build-Tools 35.0.0 + platform-tools.
- `local.properties` с `sdk.dir=...` (не коммитится).

```bash
cd android
./gradlew :app:assembleDebug
./gradlew :core:crypto:testDebugUnitTest
./gradlew :core:crypto:connectedDebugAndroidTest   # нужен подключённый телефон
```

APK: `android/app/build/outputs/apk/debug/app-debug.apk`.

### Windows и не-ASCII путь к профилю

Если `%USERPROFILE%` содержит кириллицу, Gradle Test Worker может не найти
`GradleWorkerMain`. Обходной путь — ASCII-путь для Gradle Home:

```powershell
New-Item -ItemType Junction -Path C:\Android\gradle-home -Target "$env:USERPROFILE\.gradle"
$env:GRADLE_USER_HOME = "C:\Android\gradle-home"
$env:TEMP = "C:\Android\temp"; $env:TMP = "C:\Android\temp"
```

## Сервер

```bash
cd server
./gradlew test installDist
./gradlew run          # http://localhost:8080/health
```

Docker:

```bash
cd server
docker compose up --build
```

## Бот Enchat Bridge

```bash
cd bots/enchat-bridge
python -m venv .venv
. .venv/bin/activate
pip install -r requirements-dev.txt
python -m pytest -q
python -m enchat_bridge.main
```
