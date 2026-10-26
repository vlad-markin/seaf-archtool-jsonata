# Коды ошибок движка (`X`)

Коды эталона (`S`, `T`, `D`, `U`) и их тексты — как в `errorCodes` jsonata-js 2.2.2 (`compat/jsonata-js/src/jsonata.js`). Собственные ошибки движка — в том же формате с буквой `X` (решение 18). Номер — **следующий свободный** в момент коммита (`docs/compat-policy.md`); тексты лежат в `parser/Errors.kt` (`engineTemplates`). В дизайне (`docs/plan/design.md` §3.2) коды названы символьными именами `X-…`; ниже — их номера.

| Код | Имя в дизайне | Текст | Где возникает |
|---|---|---|---|
| X0001 | — | A function named {{value}} is already registered; registered names cannot be replaced | регистрация: имя уже занято в реестре или в цепочке |
| X0002 | — | The evaluation exhausted the JVM stack. Check for non-terminating recursion or rewrite it as tail-recursive | последняя защита: стек JVM исчерпан вне границ сегментов (DEV-02) |
| X0003 | — | Invalid regular expression {{value}}: {{{message}}} | литерал regex, недопустимый в JS (DEV-04) |
| X0004 | — | The standard library registry is read-only; register {{value}} in FunctionRegistry.create() instead | регистрация в `FunctionRegistry.standard()` |
| X0005 | процессный бюджет сегментов (У1) | The evaluation needs more stack segments than the process budget allows ({{value}} running at once); see StackSegments.budget | глубокая рекурсия сверх `StackSegments.budget` (`docs/limits.md`) |
| X0006 | X-NAME | {{value}} cannot be called from an expression and cannot be registered as a function name | регистрация: `$` + имя не даёт один токен переменной |
| X0007 | X-STDLIB | {{value}} is a standard library function; register it with overridesStandard to replace it | регистрация имени stdlib без `overridesStandard` |
| X0008 | X-INTERNAL | Internal engine error: {{{message}}} | исключение JVM, которое не пришло из функции хоста (ошибка движка; причина — `cause`) |
| X0009 | X-CANCEL | The evaluation was cancelled | отмена: `CancellationToken.cancel()`, `Thread.interrupt()` потока вычисления, `InterruptedException` функции хоста (`cause`) |
| X0010 | X-BUDGET | The evaluation exceeded the {{{budget}}} budget ({{value}}) | бюджет `steps`, `string` или `result` (`EvaluationOptions.budgets`; имя — `details["budget"]`, лимит — `value`) |
| X0011 | X-WATCH | Function {{value}} is not available in a watch expression | выражение наблюдения, условие точки или logpoint вызывает функцию с `sideEffects` |
| X0012 | X-SCHEMA | Invalid schema in $jsonschema: {{{message}}} | `$jsonschema`: схема, которую ajv ядра не компилирует (строгий режим, метасхема draft-07, regex, `$ref`) |
| X0013 | — | The value is too deep to validate against a JSON Schema (more than {{value}} levels) | `$jsonschema` и проверка функций манифеста: значение глубже 1000 уровней (`docs/limits.md`) |
| X0014 | X-MF-CONFLICT | Manifest functions conflict with built-in functions: {{{message}}} | `SeafEnvironment.create`: id функции манифеста совпадает с функцией stdlib или ядра; в `message` — все конфликты: `id (stdlib|core): rename the manifest function, e.g. to <свободное имя>` (`id_mf`, `id_mf2`…, свободное по stdlib, ядру и разделу `functions`) |
| X0015 | X-MF-PARAMS | Ошибка валидации входных параметров функции [{{{value}}}] + JSON нарушений с отступом 4 | аргументы функции манифеста не прошли `params`; нарушения — `details["violations"]` (`instancePath`, `schemaPath`, `keyword`, `params`, `message`), `functionId` — id |
| X0016 | X-MF-RESULT | Ошибка валидации результата функции [{{{value}}}] + JSON нарушений с отступом 4 | результат не прошёл `result` |
| X0017 | X-MF-SCHEMA | Ошибка схемы {{{part}}} в декларации функции [{{{value}}}]: {{{message}}} | схема `params` или `result` не компилируется (как ajv ядра) — при первом вызове; `part` — «входных параметров» или «результата» |
| X0900–X0999 | — | шаблон хоста (`HostErrorCodes.register`) | функции хоста со своим кодом; коды эталона и `X0001`–`X0899` регистрировать нельзя |

Кроме кода, у ошибки есть `position` (как у эталона), диапазон узла `start`/`end`, `token`, `value`, стек кадров `frames` (имя функции или лямбды, место вызова; кадры хвостовых вызовов заменяются, как стек трамплина) и `functionId` функции манифеста (история 3). Исключение функции хоста выходит наружу тем же объектом; место в выражении хост получает через `EvaluationOptions.hostErrors` (один раз на объект исключения).

Перевод в `innerCode` ошибок сервиса — фаза 9, по этой таблице, без разбора текста.
