# CI workflows

Активные workflows лежат в `.github/workflows/`:

- `android.yml` — unit-тесты `core:crypto` и debug APK;
- `server.yml` — тесты и дистрибутив relay;
- `release.yml` — tag-triggered release APK/AAB, серверный tarball и GitHub Release.

Копии сохранены в `ci/github-actions/` как резерв и для переноса в другой форк.
Секреты подписи и release-чеклист — в `docs/release.md`.

Изначально PAT не имел scope `workflow`, поэтому файлы складывались сюда.
Сейчас пайплайны активны; при смене токена убедитесь, что у него есть
`repo` + `workflow`.
