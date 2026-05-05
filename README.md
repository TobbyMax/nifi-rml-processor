# nifi-rml-processor

Курсовая работа: **"Реализация новых методов автоматической трансформации данных на платформе Apache NiFi с использованием языка RML и YARRRML"**.
Направление: **Бизнес-информатика**. Автор: М. Агеев.

## Что это

Кастомные процессоры **Apache NiFi 2.8.0**, которые нативно исполняют декларативные маппинги [RML](https://rml.io/specs/rml/) и [YARRRML](https://rml.io/yarrrml/spec/) для трансформации **JSON / CSV / XML → RDF** (Turtle, N-Triples, JSON-LD, RDF/XML) внутри потоков данных NiFi. Решение построено вокруг гибридного движка с тремя режимами: **RMLMAPPER** (in-process), **MORPH_KGC** (subprocess), **AUTO** (выбор по размеру файла).

## Состав репозитория

| Раздел | Назначение |
|---|---|
| [`PLAN.md`](PLAN.md) | Согласованный исследовательский план (источник истины) |
| `docs/` | Теоретические главы (этапы 1-3), архитектура (этап 4), методики (этапы 6, 8), экономика (этап 9), бизнес-ценность, библиография |
| `nifi-rml-processors/` | Java-модуль с процессорами и engine layer |
| `nifi-rml-nar/` | Сборочный модуль NAR-bundle для деплоя в NiFi |
| `flows/` | Тестовые NiFi DataFlow definitions (этап 7) |
| `evaluation/` | Методика и инфраструктура бенчмарков (этап 8) |

## Сборка

```bash
./gradlew build              # компиляция + тесты
./gradlew test               # только unit-тесты
./gradlew :nifi-rml-nar:nar  # сборка NAR для NiFi
```

NAR-файл появится в `nifi-rml-nar/build/libs/`. Для деплоя — скопировать в `$NIFI_HOME/extensions/` и перезапустить NiFi.

## Требования

- **Java 21+** (требование Apache NiFi 2.x).
- **Gradle 8+** (поставляется через `./gradlew`).
- Для режима `MORPH_KGC` дополнительно: **Python ≥ 3.9** и `pip install morph-kgc` на NiFi-хосте.

## Ветки git

- **`master`** — финальная версия с поддержкой JSON+CSV+XML и обоих движков.
- **`etap-5-json-only`** — снимок после этапа 5 (только JSON), сохранён для иллюстрации фазового подхода в ВКР.

## Лицензия

Apache-2.0 (совместимая с экосистемой Apache NiFi и RMLMapper).
