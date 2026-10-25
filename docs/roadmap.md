# План: JSONata на JVM

Тикет: **ERA-0000** (временный; настоящий номер подставим перед переносом на Gitverse).

Фазы 0, 6 и 9 требуют core, архитектурного озера и решений владельца, поэтому ведутся локально. Фазы 1–5 и 8 механические и хорошо проверяются тестами, их ведут облачные сессии. Фазы 1–5 от решений по API и host-функциям не зависят.

Цифры test-suite jsonata-js 2.2.2: всего 1686 случаев. 791 из них — язык (группы без `function-`/`hof-`), 895 — стандартная библиотека (`function-*`, `hof-*`). Эталонных AST тоже 1686, из них 41 ожидаемая ошибка разбора.

## Фаза 1. Репо и скелет

- [x] Gradle (Kotlin 2.4, байткод 21, foojay), модули `jsonata-core`, `jsonata-json`, `jsonata-compat`
- [x] Вендоринг jsonata-js v2.2.2: `src/`, `test-suite/`, `implementation-tests.js`, `run-test-suite.js`, MIT LICENSE, `VERSION`
- [x] Эталонные AST (`compat/node/dump-ast.mjs`), раннер с храповиком и отчётом, проверки целостности
- [x] Корпус реальных выражений SEAF в `compat/corpus/` — **локально** (см. `compat/corpus/README.md`), снапшоты корпуса — тем же `dump-ast.mjs`
- [x] Раннер `compat/jsonata-js/implementation-tests.js`: перенести в Kotlin-тесты те проверки, что относятся к языку, а не к JS API (`assign`, `registerFunction`, обработка ошибок) — по мере фаз 3–5

**Готово, когда:** `./gradlew build` зелёный на чистой машине, отчёт совместимости формируется.

## Фаза 2. Модель данных

Пакет `su.seaf.archtool.jsonata.value` (имя — на усмотрение исполнителя, но одно место).

- [x] Значения JSON: null, boolean, number (`double`), string, array, object с порядком вставки; отдельное представление **undefined**, не совпадающее с null
- [x] Последовательность (sequence): флаги `keepSingleton`, `tupleStream`, `outerWrapper` как в `src/utils.js`/`src/jsonata.js`; схлопывание из одного элемента
- [x] Функции как значения (лямбды, нативные, частично применённые) — интерфейс, реализация в фазе 4
- [x] Числа ↔ строки по правилам JS: `Number.prototype.toString`, `JSON.stringify` (в том числе `$string` с отступами), разбор чисел литералов
- [x] `jsonata-json`: потоковое чтение и запись через Jackson core без промежуточного дерева, с сохранением порядка ключей и различием «нет поля» / `null`
- [x] Модульные тесты: краевые числа (`1e21`, `1e-7`, `-0`, `0.1+0.2`, `2^53+1`), Unicode, порядок ключей, большие документы (чтение без переполнения стека на глубокой вложенности)

**Готово, когда:** модель покрыта модульными тестами, `jsonata-json` читает все датасеты test-suite и пишет их обратно эквивалентно.

## Фаза 3. Парсер

Перенос `src/parser.js` (токенизатор + Pratt, `processAST`): **AST должен совпадать с эталоном**.

- [x] Токенизатор: строки с экранированием и `\u`, числа, имена в обратных кавычках, регулярные выражения `/.../flags`, комментарии `/* */`, операторы
- [x] Pratt-парсер со всеми nud/led и приоритетами из `parser.js`
- [x] `processAST`: пути, предикаты, `group`, `sort`, `@`/`#`-привязки, `%` (ancestor-слоты, `seekingParent`), `keepArray`, `tuple`, лямбды и сигнатуры (`signature.js` — разбор сигнатуры; в AST есть `definition`, а `validate` в снапшоте заменён маркером `{"@function": "validate"}`)
- [x] Ошибки разбора: коды `S0xxx`, `position`, `token` как в эталоне (41 случай в снапшотах)
- [x] `KotlinEngine.ast` в `jsonata-compat` переводит AST в `JsonNode` в формате снапшота (regex → `{"@regex", "flags"}`)
- [x] Режим `recover` эталона **не** переносим (не используется в core)

**Готово, когда:** храповик `compat/pass/ast.txt` = 1686/1686, корпус SEAF (если уже собран) — 100%.

**Состояние 2026-10-11:** ast 1686/1686, корпус 1215/1215 (выражения метамодели SEAF, `CorpusAstTest`; корпус локальный, в публичный репо не входит). Публичный API парсера: `JsonataParser.ast`/`analyze` (токены с позициями для IDE).

## Фаза 4. Вычислитель v0

Компиляция AST в замыкания (дерево `(Input, Frame) -> Value`), без оптимизаций. Опора — `src/jsonata.js`, функция за функцией (`evaluatePath`, `evaluateStep`, `evaluateTupleStep`, `evaluateFilter`, `evaluateBinary`, `evaluateGroupExpression`, `evaluateSortExpression`, `evaluateTransformExpression`, `apply`/`applyInner`, `partialApply`, `evaluateLambda`…).

- [x] Пути, шаги, предикаты, индексы, `[]`/keepSingleton, схлопывание последовательностей, массивы массивов (flattening)
- [x] Операторы: арифметика (ошибки `T2001`…), сравнение, `and`/`or`, `in`, `&`, `..`, `? :`, `?:`, `??`, `~>`
- [x] Блоки и переменные `:=`, области видимости фреймов, привязки (`bindings`)
- [x] Конструкторы объектов и массивов, группировка `{}` с ключами из выражений, сортировка `^( )`
- [x] Привязки `@` и `#`, родитель `%`
- [x] Лямбды, замыкания, частичное применение `?`, сигнатуры (`src/signature.js`: валидация и приведение аргументов, ошибки `T0410`…)
- [x] Хвостовые вызовы без роста JVM-стека (группа `tail-recursion`)
- [x] Трансформация `|...|...|` (группы `transform`, `transforms`)
- [x] Точка `checkpoint()` во всех циклах и вызовах; `timelimit`/`depth` из test-suite → `U1001` как в `timeboxExpression`
- [x] Регистрация нативных функций с сигнатурой — механизм, которым воспользуется фаза 5 (и позже фаза 6)

**Готово, когда:** в храповике `eval` все 791 случай групп языка, кроме тех, что зависят от ещё не реализованных функций stdlib (такие перечислить в отчёте фазы).

## Фаза 5. Стандартная библиотека

Перенос `src/functions.js` и `src/datetime.js` (около 70 функций), сигнатуры — как в регистрации `jsonata.js`.

- [x] Строки: `$string`, `$length`, `$substring*`, `$uppercase`/`$lowercase`, `$trim`, `$pad`, `$contains`, `$split`, `$join`, `$match`, `$replace`, `$base64*`, `$encodeUrl*`/`$decodeUrl*`, `$eval`
- [x] Числа: `$number`, `$abs`, `$floor`, `$ceil`, `$round` (банковское округление как в эталоне), `$power`, `$sqrt`, `$random`, `$formatNumber`, `$formatBase`, `$formatInteger`, `$parseInteger`
- [x] Агрегаты, логика, массивы, объекты: `$sum`/`$max`/`$min`/`$average`/`$count`, `$boolean`/`$not`/`$exists`, `$append`/`$reverse`/`$shuffle`/`$distinct`/`$zip`/`$sort`, `$keys`/`$lookup`/`$spread`/`$merge`/`$sift`/`$each`/`$error`/`$assert`/`$type`
- [x] HOF: `$map`, `$filter`, `$single`, `$reduce`
- [x] Регулярные выражения JS → `java.util.regex`: один транслятор, тесты на отличия; `$match`, `$replace` с функцией, `$split`, `$contains`
- [x] Даты: `$now`, `$millis`, `$fromMillis`, `$toMillis` (картинки XPath `[Y0001]-[M01]…`, часовые пояса), `java.time`
- [x] `$sort`: та же устойчивая сортировка слиянием, что в эталоне (порядок вызовов компаратора)

Трудные группы по числу случаев: `function-fromMillis` 93, `function-formatInteger` 65, `function-tomillis` 60, `function-formatNumber` 45, `regex` 39.

**Готово, когда:** храповик `eval` = 1686/1686, кроме случаев, перечисленных в `docs/deviations.md` с причиной (например, `$now`/`$millis`/`$random`/`$shuffle`, если тест зависит от недетерминизма).

**Состояние 2026-10-25:** eval 1686/1686 без исключений; ловушки из оценки dashjoin — `compat/traps` (398 случаев, тест `TrapsTest`).

## Фазы 6–9 (не в облаке, кратко)

6. Функции SEAF: host-функции core на Kotlin (`lookup`, `tryexecute`, `set`/`get`, `log`, `manifestschema`, `sourcetype`, `wcard`, `mergedeep`, `jsonschema`, `parsesource` с загрузкой ресурсов через хост), пользовательские функции из `manifest.functions`.
7. Управление вычислением: отмена прерыванием потока, лимиты шагов, времени и глубины, ошибки в формате правил SEAF.
8. Совместимость: дифф-фаззинг против jsonata-js через Node-сайдкар, replay реальных запросов, отчёт расхождений.
9. Производительность (ленивые последовательности, оптимизации под флагами, JMH на больших архитектурах), `jsonata-service` (сессии по id + разовый вызов), `jsonata-host`, публикация в Maven Central, переключатель в core.
