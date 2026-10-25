# Привязка к коду

| Репозиторий | Ветка | Коммит | Примечание |
|---|---|---|---|
| `seaf-archtool-jsonata` | `origin/dev` | `9bb21aa` | Основная ветка (github.com/vlad-markin/seaf-archtool-jsonata): каркас и парсер (`58d078e`), порядок ключей ORD-01 (`682ad1b`), решение 17 (`c8c951a`), задание облаку по ревью фаз 2–5 (`docs/review/phases-2-5.md`). Вычислителя здесь ещё нет |
| `seaf-archtool-jsonata` | `origin/feature/ERA-0000-kotlin-engine-phases` | `06fa7f9` | Фазы 2–5 (до `5bd621a`), слияние `dev` (`9310e28`), исправления ревью RV-1…RV-16, временные лимиты по умолчанию (`d96cc2b`), дифф-фаззинг фазы 8 (`bb14daf`, `62720ca`), ускорение без смены поведения. `ast` и `eval` 1686/1686. В `dev` не влита: `baseline_commit` историй — `06fa7f9` до коммита слияния |
| `seaf-archtool-core` | `origin/dev` | `0f01ced3` | 2026-10-23 |
| `seaf-archtool-language` | `dev` | `a1c874d` | Remote нет |
| jsonata-js (эталон) | тег v2.2.2 | `6c7e95f` | Вендоринг в `seaf-archtool-jsonata/compat/jsonata-js/` |
| Оценка порта dashjoin | `vlad-markin/jsonata-java`, ветки `claude/eval-js-2.2.2` и `claude/debug-api` | — | Отчёт `compat-eval/REPORT.md` и соседние файлы |

## Как сейчас

Пути движка — по ветке фаз `06fa7f9`, если не сказано иное; `…` = `jsonata-core/src/main/kotlin/su/seaf/archtool/jsonata`.

| Что | Где |
|---|---|
| Решения владельца: компромиссы (12), заморозка диалекта (13), свои функции (14), отладка (15), лицензия (16), отличия встроены всегда, режима «как JS» нет, флаг инсталляции (17), реестр без подмены и коды `X` (18) | `docs/context.md:26-32` |
| Функции манифеста и host-функции ядра — решения 6 и 7 | `docs/context.md:20-21` |
| Правила совместимости: три вида расхождений, компромисс только с согласия, заморозка, коды ошибок, храповик | `CLAUDE.md:45-49` |
| Порядок ключей объектов — как у объектов JS (ORD-01): `$keys({"b":1,"2":2,"1":3,"01":4})` → `["1","2","b","01"]` | `CLAUDE.md:58` (с `682ad1b`) |
| Запрет новых зависимостей в `jsonata-core`; модули — `jsonata-core`, `jsonata-json`, `jsonata-compat` (модулей `jsonata-seaf`, `jsonata-tools` нет) | `CLAUDE.md:76`; `settings.gradle.kts:6` |
| Фазы 6–9 описаны одной строкой каждая, без критериев готовности | `docs/roadmap.md:82-87` |
| Публичный API: `Jsonata.compile/evaluate/ast`, `CompileOptions(debug)`, `EvaluationOptions(maxDepth, timeoutMillis, maxSequence, clock)` | `…/Api.kt:19-97` |
| Временные лимиты по умолчанию: `maxDepth` 1 000 000 вложенных шагов (около 200 000 уровней рекурсии), `maxSequence` 1e7, дедлайна нет | `…/Api.kt:76-97` (`d96cc2b`) |
| `NativeImplementation(context, args)`, `CallContext{input, timestamp, call}`: отмены, дедлайна и вложенного вычисления нет | `…/Api.kt:100-114` |
| `FunctionRegistry`: цепочка `standard()` → `extend()`/`create()`; занятое имя → `X0001`; `standard()` заморожен, регистрация в него → `X0004`. Нет документации, признака переопределения, проверки вызываемости имени, области «на одно вычисление» | `…/Api.kt:116-178`; тексты `X0001`–`X0004` — `…/parser/Errors.kt:144-147` |
| Вычислитель: `Evaluator.run`; `StackOverflowError` → `X0002` — последняя защита | `…/engine/Evaluator.kt:23-37` |
| `EvalContext`: `enter()` и `checkpoint()`; сегментированный стек — `onNewSegment`: после 120 уровней на потоке вызывающего и далее каждые 20 000 вычисление продолжается на новом платформенном потоке со стеком 256 МБ (Р1, ревью RV-4) | `…/engine/Runtime.kt:73-182` |
| Нехвостовая рекурсия 100 000 уровней и данные такой же вложенности (обходы без рекурсии) — тест `EngineTest`; запись DEV-02 | `jsonata-core/src/test/.../engine/EngineTest.kt`; `docs/deviations.md` |
| Хук отладки `DebugHook` (внутренний): узел вызывает его сам, если выражение скомпилировано с `debug = true`; результат не меняется (ревью RV-1) | `…/engine/Runtime.kt:190`; `…/engine/Nodes.kt:44-66` |
| У узла AST только `position` (смещение после токена, как в эталоне). Диапазона `[start, end)` у узлов нет, у токенов есть | `…/parser/Ast.kt:18-19`, `:98-101` |
| Разбор сигнатур `<s-n?:s>` (`Signature.parse`) | `…/parser/Signature.kt` |
| stdlib: 63 функции `staticFrame` и `now`/`millis` | `…/engine/StandardLibrary.kt:21-111` |
| `$eval` оборачивает любую `JsonataException` в `D3120`/`D3121`, как 2.2.2 | `…/engine/StandardLibrary.kt:759-777` |
| Кэш regex — LRU с границей (ревью RV-13) | `…/engine/Regex.kt:22-34` |
| Храповик: `compat/pass/ast.txt` и `eval.txt` — по 1686 строк | `compat/pass/*.txt` |
| Ловушки: `divergences.jsonl`, `targeted.jsonl`, `review.jsonl`, `fuzz.jsonl`; эталон `expected.jsonl` снимается на Node 22 | `compat/traps/README.md` |
| Дифф-фаззинг (фаза 8): эталон-сайдкар `compat/node/sidecar.mjs`, генератор по 12 зонам риска, `./gradlew :jsonata-compat:fuzz`; кампания 300 тыс. случаев — 0 необъяснённых расхождений | `jsonata-compat/.../compat/fuzz/DiffFuzz.kt`, `Generator.kt`; коммит `bb14daf` |
| Отличия DEV-01…DEV-09 (вход не мутируется; глубина и `X0002`; сигнатура при частичном применении; regex `X0003`; UTF-16 в regex; регистр с `i` — Р3; `$toMillis` без смещения — UTC, Р2; пустой массив в конструкторе; первая ошибка конструктора). Полей «категория, решение с датой, версия, тест» нет, правило детектора — текстом | `docs/deviations.md` |
| Кандидаты P-01…P-04 без поля «Категория» и статуса; P-01…P-03 приняты владельцем 2026-10-25, но не реализованы. Проверено на `jsonata@2.2.2`: `x.a[true]` → `[1, undefined, undefined]`, `[{"a":1},{"a":2}][0].a` → `undefined` | `docs/proposals.md` |
| `docs/compat-policy.md`, `docs/limits.md`, страницы для архитекторов (`docs/user/…`) ещё не созданы | — |

## Эталон jsonata-js 2.2.2 (`compat/jsonata-js/src`)

| Что | Где |
|---|---|
| Хуки входа и выхода узла через `Symbol.for('jsonata.__evaluate_entry/exit')` — из выражения недоступны | `jsonata.js:54`, `jsonata.js:128` |
| 63 встроенные функции с сигнатурами (`staticFrame.bind`) | `jsonata.js:1909-1971` |
| Опции-ограничители `timeout`/`stack`/`sequence` → `D1012`/`D1011`/`D2015` | `jsonata.js:2118-2122`, `jsonata.js:2203-2220`, `jsonata.js:2177-2181` |
| Лимит диапазона `D2014`, последовательности `D2015` | `jsonata.js:1064-1071` |
| `registerFunction(name, implementation, signature)` молча переопределяет stdlib: `registerFunction('sum', ()=>42)` → `$sum([1,2])` = 42 (проверено на `jsonata@2.2.2`) | `jsonata.js:2252` |
| Сортировка слиянием через `slice(1)` — квадратична по времени и памяти | `functions.js:1958-1970` |
| Ограничение времени и глубины в раннере test-suite (`timeboxExpression`, `U1001`) | `compat/jsonata-js/run-test-suite.js:82-85`, `:156` |

## Ядро: функции и драйвер JSONata (`seaf-archtool-core`, `origin/dev` 0f01ced3)

| Что | Где |
|---|---|
| Функции ядра `sourcetype`, `wcard`, `mergedeep`, `jsonschema` | `src/global/jsonata/driver.mjs:292-297` |
| `appendCoreFunctions` — дорегистрация (`versions` на backend и frontend); повтор имени только пишет в лог | `driver.mjs:305-310`, `src/backend/helpers/jsonata/versionsFunc.mjs:49`, `src/frontend/helpers/jsonata/versionsFunc.js:58` |
| `lookup` ядра переопределяет стандартную `$lookup`: ключ `null` → `null` (в 2.2.2 `$lookup({"k":1}, null)` → `T0410`), форма с одним аргументом берёт контекст, массив разворачивается | `driver.mjs:267-290`, регистрация `driver.mjs:399` |
| `parsesource` — единственный вызов хоста (драйвер датасетов) | `driver.mjs:68-70`, `:400` |
| `tryexecute` → `{success, result, error}`; текст ошибки достаётся повторным `evaluate` | `driver.mjs:316-356`, `:401` |
| `manifestschema`, `log` (выключается `VUE_APP_DOCHUB_JSONATA_LOG_FUNC_ENABLE`, переопределяется `funcs.log`), `set`/`get` (хранилище `obj.store` — живёт столько же, сколько объект запроса, переживает повторные `evaluate`) | `driver.mjs:402`, `:416-428`, `:384` |
| Порядок регистрации: функции ядра → `customFunctions` (функции манифеста) → `log` → `set`/`get` → `funcs`. Последняя регистрация молча побеждает | `driver.mjs:399-431` |
| `$self`, `$params` — `assign` на каждое выражение | `driver.mjs:396-397` |
| «Трассировка» — только время начала и конца и результат в логе при `isTrace` | `driver.mjs:434-448` |
| Журнал производительности функций (`PerformanceLogger`, `VUE_APP_DOCHUB_PERF_LOGGER_ENABLE`) | `driver.mjs:358`, `:365`, `:403-411` |
| Ограничители jsonata-js не используются: `jsonata(this.expression)` без опций | `driver.mjs:395` |
| Функции манифеста: валидатор параметров (ajv, `alias`/`param<N>`, `required`, ключевые слова `callback` и `params` = «функция») | `src/global/jsonata/functions.mjs:42-95` |
| Параметры-лямбды уходят в `funcs` и регистрируются как функции, остальные — поля входа `context` | `functions.mjs:121-146` |
| Без `params` валидатора нет, вход функции `undefined` | `functions.mjs:122-124`, `:199` |
| Выражение функции пересобирается при смене лямбды-аргумента | `functions.mjs:178-198` |
| Ошибки валидации — `Error` с русским текстом и JSON ajv, без кода JSONata | `functions.mjs:142`, `:158` |
| Валидаторы `params`/`result` — ajv 8 (`new ajv({allErrors:true})`, строгий режим по умолчанию, без `ajv-formats`), компилируются при первом вызове функции; `pattern` — regex JS | `functions.mjs:65`, `:105`, `:113-123`, `:176` |
| `$jsonschema` — ajv 8 с `ajv-formats`, `unicodeRegExp: false` | `driver.mjs:37-38`, `:79-82` |
| `$tryexecute` ловит любое исключение | `driver.mjs:316-356` |
| Подключение функций манифеста на backend (по ролям) и во frontend | `src/backend/storage/harness.ts:52`, `:68`; `src/frontend/manifest/query.js:214-222` |
| Схема раздела `functions`: `title`, `params` (JSON Schema), `code` (обязателен), `result`; id `^[a-zA-Z0-9_]*(\.[a-zA-Z0-9_]*)*$` | `src/assets/master-schema.yaml:269-292` |
| Пользовательская документация функций манифеста (beta), пример `$demo_summ` | `public/documentation/docs/manual/functions/functions.md` |

Проверено на `jsonata@2.2.2`: функция с id `a.b`, зарегистрированная `registerFunction('a.b', …)`, из выражения `$a.b(2)` не вызывается. Результат — `undefined`, ошибки нет.

## Языковой сервер (`seaf-archtool-language`, `dev` a1c874d)

| Что | Где |
|---|---|
| Подключает `jsonata-core` (парсер) через composite build | `settings.gradle.kts:8-10`, `lang-core/build.gradle.kts:12-13` |
| Своя таблица stdlib с сигнатурами (`BUILTINS`) | `lang-core/src/main/kotlin/su/seaf/archtool/language/core/jsonata/JsonataLanguage.kt:27-28` |
| Своя таблица функций ядра (`CORE`) с описаниями на русском. Нет `lookup` (переопределения) и `versions` | `JsonataLanguage.kt:45-56` |
| Переменные ядра `self`, `params` | `JsonataLanguage.kt:57` |
| Автодополнение и hover: функции манифеста (сигнатура из `params`), ядра, stdlib | `JsonataLanguage.kt:124-175` |
| DAP нет | — |

## Оценка порта dashjoin (вход для требований)

| Что | Где (репозиторий `vlad-markin/jsonata-java`) |
|---|---|
| 50 расхождений с 2.2.2, решения, примеры: исправлено 38, исключений 7, «решение за владельцем» 5 (ORD-01, STR-03, DT-06, API-06, ERR-03) | `compat-eval/divergences.md` (ветка `claude/eval-js-2.2.2`) |
| Вопросы к владельцу, которые к своему движку применимы: ORD-01, STR-03, DT-06, политика ошибок эталона (вопрос 6) | `compat-eval/REPORT.md`, раздел «Вопросы» |
| Строгий test-suite 1685/1686, фаззинг 42 тыс. случаев: расхождения 10,7% → 3,3% (остаток — ORD-01 и STR-03) | `compat-eval/REPORT.md`, «Совместимость» |
| Скорость против jsonata-js: 2,3–11× (10 тыс.), 1,2–6× (50 тыс.), 1,2–3,7× (200 тыс.), `join_scan` 200 тыс. — 0,9×; память 3–5×; компиляция < 0,5 мс; методика и стенд | `compat-eval/REPORT.md`, «Производительность»; `compat-eval/phase5/performance.md`; `compat-eval/bench/` |
| Контракт управления: отмена, дедлайн, бюджеты, структурированная ошибка, функции хоста с контекстом; найденные подмены исключений (`and`/`or`, `$sort`, `$replace`, `$eval`) и утечки окружения | `compat-eval/phase5/control.md` |
| Контракт отладчика (часть A): диапазоны, события, точки останова, шаги, области, стек, ошибка, потоки, SPI; прототип (+128 строк, около +25% с подключённым отладчиком); пробелы B.4 | `compat-eval/debugger-api.md`, ветка `claude/debug-api` |
| Детектор и переписыватель выражений по AST `ast()` (правила по id каталога) | `compat-eval/migration/README.md` |

## Корпус SEAF (локально, 1215 выражений)

Вызовов в корпусе: `$exists` 4322, `$lookup` 2553 (переопределение ядра), `$keys` 1142 и `$each` 367 (ORD-01), `$spread` 164, `$eval` 116, `$self` 384, `$params` 662. Функций ядра: `$wcard` 15, `$jsonschema` 9, `$mergedeep` 7, `$log` 6, `$manifestschema` 5, `$parsesource`/`$tryexecute`/`$set`/`$get`/`$versions` — 0. Встроенных лямбд (`function(`) — 2764. Функции манифеста именуются плоско с префиксами-соглашениями (`seaf_fn_…`, `seaf_ta_fn_…`, 32 разных имени) — пространство имён по соглашению, а не синтаксису.

## Документация

- Репозиторий движка: новые `docs/compat-policy.md` и `docs/limits.md` (временные лимиты, остаток риска по памяти); `docs/proposals.md` и `docs/deviations.md` — привести к формату CAP-1 (`P-NN`/`DEV-NN`, «Категория»); описание поведения функций ядра SEAF с эталонными значениями (CAP-6 п. 7); инструкция сборки и запуска `jsonata-tools` из исходников для владельца инсталляции (CAP-15 п. 5); страница «Отличия от jsonata-js 2.2.2» для архитекторов; документация API функций (CAP-4, CAP-8) и отладки (CAP-10, CAP-11); отчёты совместимости и производительности; раздел «Фазы 6–9» в `docs/roadmap.md` получает критерии готовности со ссылками на CAP; решения 12–15 в `docs/context.md` — ссылкой на эту спеку.
- Ядро (при переключении, отдельной задачей): `public/documentation/docs/manual/functions/functions.md` — ошибки, документация функций, отладка.

## Потребители

- `seaf-archtool-core`: backend (сейчас Node, переезжает на JVM) и frontend (браузер) вычисляют выражения и функции манифеста на jsonata-js (`driver.mjs:36`, `:395`). По флагу инсталляции CAP-16 все эти места должны работать через движок — отдельные задачи зоны core.
- Плагины IDE (JetBrains — через будущий `jsonata-host`, VS Code). Ядро в них сейчас работает на jsonata-js. Переход по флагу CAP-16 — отдельные задачи зоны ide.
- `seaf-archtool-language`: парсер `jsonata-core` уже используется, каталог функций (CAP-8) и отладочный контракт (CAP-10) — будущие потребители (DAP).
- DevTool портала (`plugins/devtool`, `src/frontend/components/JSONata/DevTool.vue`) — потребитель лимитов (CAP-13) и трассировки (CAP-11).
- Внешние потребители API и CI, завязанные на результаты запросов: отличия анонсируются (CAP-15).
