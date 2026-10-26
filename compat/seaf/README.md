# Ожидания функций SEAF

- `ajv-expected.jsonl` — результаты проверки JSON Schema ajv 8.20.0 и ajv-formats 2.1.1 в настройках ядра, снимает `compat/node/ajv-expected.mjs` (руками не правим). Тест — `SchemaConformanceTest`.
- `cases.jsonl` — выражения с функциями ядра SEAF и ожидаемым результатом (`json`, текст `JSON.stringify`). Ожидания закреплены по поведению ядра там, где его можно описать без эталона; поля из `ignore` (тексты ошибок ядра) не сравниваются и сверяются локально. Тест — `SeafCasesTest`.
