# Производительность: baseline

Устройство: `132763749E001770` (Android, 1080×2460), release-сборка.
Дата: 2026-11-08, версия 1.3.0 / versionCode 8.

## Замеры

| Метрика | Значение | Метод |
|---|---|---|
| Холодный старт (`am start -W`, TotalTime) | **353 мс** | force-stop + 3 запуска |
| Повторный запуск | 16–19 мс WaitTime | приложение в памяти |
| Размер APK | **69 743 300 байт** (~66.5 МиБ) | release APK |
| Открытие чата | не измерено инструментом | вручную, визуально быстро |
| Прокрутка 1000 сообщений | не измерено | нужен Macrobenchmark |
| Jank / память | не измерено | нужен Macrobenchmark/Perfetto |

## Известные hotspots (проверить в 1.4.0)

- `AppRepository` синхронно читает/расшифровывает vault в `Application.onCreate`.
- `VaultStore.save()` сериализует и шифрует весь `VaultData` на каждое действие.
- `MainViewModel.uiState` содержит весь vault.
- `TagaApi` создаёт новый `OkHttpClient` на каждый экземпляр.
- `ChatScreen.kt` ~70 КБ, поиск/фильтры на каждый ввод.
- Polling каждые 3 с; state push теперь только при изменении.

## Цели 1.4.0

- Cold start ≤ 1.5 с (уже лучше) и ≥ 25% улучшение, если возможно.
- Warm start ≤ 600 мс.
- Открытие чата ≤ 400 мс.
- ≤ 3% кадров > 16.7 мс.
- APK ≤ +15% к 1.3.0.
- Baseline Profile включён.

## Окружение

Из-за кириллического пути `C:\Users\Администратор\.gradle` JDK 17 иногда
неверно читает `@argfile` (ClassNotFoundException в test worker). Для
воспроизводимых прогонов использовать ASCII-путь:

```powershell
$env:GRADLE_USER_HOME="C:\Android\gradle-home"
```
