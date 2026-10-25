# Ход работ: фазы 2–5

Ветка `feature/ERA-0000-kotlin-engine-phases` (от `dev`). Обновляется при каждом пуше.

## Сейчас

- **Фаза:** 2 завершена, начинается фаза 4 (вычислитель v0).
- **Счётчики** (`jsonata-compat/build/compat-report.json`): ast 1686/1686, eval 0/1686.

## Сделано

### Фаза 2. Модель данных

- `su.seaf.archtool.jsonata.value`: `Value` (sealed) — `NullValue`, `BooleanValue`, `NumberValue` (double, `-0` сохраняется), `StringValue`, `ArrayValue` (флаги последовательности `isSequence`/`keepSingleton`/`tupleStream`/`outerWrapper`/`cons`), `ObjectValue`, `FunctionValue`. **undefined — это Kotlin `null`**, а не значение; JSON `null` — `NullValue`.
- Порядок ключей `ObjectValue` — как у объекта JS (ловушка ORD-01, правило CLAUDE.md): сначала ключи-индексы (`0`…`2^32-2`) по возрастанию, затем остальные в порядке вставки; перезапись ключа сохраняет его место.
- `JsNumber`: `Number.prototype.toString` (на основе кратчайшего представления JDK 19+ с поправкой для субнормальных, где JDK берёт 2 цифры: `4.9E-324` → `5e-324`), `toPrecision`, `toFixed`, `toString(radix)`. Сверено с V8 на 6000 случайных double (`js-numbers.txt`, сгенерирован Node 22).
- `JsonWriter`: `JSON.stringify` (экранирование как в JS, включая одиночные суррогаты, отступы) и режим `$string` (15 значащих цифр, функции → `""`, D1001 на бесконечности внутри структуры).
- `jsonata-json`: `JsonataJson.parse/stringify/write` на Jackson streaming без дерева и без рекурсии (вложенность ограничена только памятью), числа — double с корректным округлением, дубликаты ключей — как `JSON.parse`. Все датасеты test-suite читаются и пишутся обратно эквивалентно.

## Следующее

Фаза 4: компилятор AST → узлы-замыкания, пути и последовательности, операторы, фреймы, лямбды и трамплин, реестр функций с сигнатурами.

## Вопросы

Пока нет.
