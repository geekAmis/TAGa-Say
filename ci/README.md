# CI workflows (pending `workflow` scope)

GitHub отклонил push файлов из `.github/workflows/`, потому что у текущего PAT нет
scope `workflow`:

```
refusing to allow a Personal Access Token to create or update workflow
.github/workflows/android.yml without `workflow` scope
```

Файлы сохранены здесь, чтобы не потерять. Чтобы включить CI:

1. Создайте/обновите PAT с правами `repo` + `workflow` (или используйте fine-grained
   token с `Workflows: Read and write`).
2. Скопируйте `android.yml` и `server.yml` в `.github/workflows/`.
3. Закоммитьте и запушьте.

Локально файлы уже готовы и проверены синтаксически.
