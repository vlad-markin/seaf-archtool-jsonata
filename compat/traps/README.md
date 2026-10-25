# Ловушки из оценки dashjoin/jsonata-java

Случаи взяты из публичного репо `vlad-markin/jsonata-java`, ветка `claude/eval-js-2.2.2`, каталог `compat-eval/cases/` (Apache 2.0):

- `divergences.jsonl` — минимальные примеры к 50 позициям каталога расхождений (`compat-eval/divergences.md`, id вида `NUM-02#1`);
- `targeted.jsonl` — точечные проверки неочевидных мест (сортировка, regex, даты, числа, строки, порядок ключей).

`review.jsonl` — случаи из ревью фаз 2–5 (`docs/review/phases-2-5.md`): именованные группы regex (RV-10), регистр и ссылка вперёд (RV-11).

`expected.jsonl` — результаты эталона `jsonata@2.2.2`, сгенерированные `compat/node/traps.mjs` (`cd compat/node && npm ci && node traps.mjs`); руками не правим. Генерировать на **Node 22** (`compat/node/.nvmrc`, `engines` в `package.json`, скрипт проверяет версию): на других версиях V8 меняются последние биты `$power` (NUM-11#1). Тест `TrapsTest` в `jsonata-compat` сравнивает с ними движок: значение — текстом `JSON.stringify` (порядок ключей тоже), ошибка — по коду. Расхождения, допустимые по `docs/deviations.md` или нестабильные у эталона (время, случайность), перечислены в тесте с причиной.
