# nifi-rml-processor

Курсовая работа: **«Реализация новых методов автоматической трансформации данных на платформе Apache NiFi с использованием языка RML и YARRRML»**.
Направление: **Бизнес-информатика**. Автор: М. Агеев.

## Что это

Кастомные процессоры **Apache NiFi 2.8.0**, которые нативно исполняют декларативные маппинги [RML](https://rml.io/specs/rml/) и [YARRRML](https://rml.io/yarrrml/spec/) для трансформации **JSON / CSV / XML → RDF** (Turtle / N-Triples / JSON-LD / RDF-XML) внутри потоков данных NiFi.

Ключевые возможности:

- **Гибридный движок** с тремя режимами выбора:
  - `RMLMAPPER` — in-process Java-движок (поверх Apache Jena);
  - `MORPH_KGC` — внешний Python-движок через `ProcessBuilder`;
  - `AUTO` — выбор по размеру FlowFile (default 50 МБ, настраивается).
- **Потоковый режим**: записи читаются лениво (Jackson streaming для JSON, ленивый итератор для CSV, StAX-style XPath для XML), триплеты эмитируются через `Jena StreamRDF` без построения in-memory графа. Pиковая память ограничена размером одной записи, не всего FlowFile.
- **Несколько источников маппинга** (`mapping-source`):
  - `INLINE` — текст в свойстве процессора;
  - `FILE` — путь к файлу на диске NiFi-узла;
  - `ATTRIBUTE` — FlowFile-атрибут;
  - `URL` — HTTP(S) / `file://` / `classpath:` URI с TTL-кешем; работает с raw-endpoint'ами GitHub/GitLab, S3, внутренними mapping-репозиториями.
- **Параметризация**: NiFi Expression Language применяется к маппингам и URL — `${tenant.id}`, `${dataset}`, `${region}` подставляются из FlowFile-атрибутов.
- **YARRRML**: встроенная транспиляция YARRRML→RML через свойство `mapping-format` в `ExecuteRMLMappingProcessor` (базовое подмножество спецификации, Java-парсер).
- **Provenance**: на каждый FlowFile пишутся атрибуты `rml.engine.selected`, `rml.engine.reason`, `rml.triples.count`, `rml.duration.ms`, `rml.input.size.bytes`, `mime.type`, `rml.error.*`.

## Состав репозитория

| Раздел | Назначение |
|---|---|
| `nifi-rml-processors/` | Java-модуль с процессорами, engine layer, YARRRML-парсером |
| `nifi-rml-nar/` | Сборочный модуль NAR-bundle для деплоя в NiFi |
| `flows/` | 5 готовых blueprint-описаний DataFlow (этап 7) + скрипты импорта/валидации |
| `evaluation/` | Методика и инфраструктура бенчмарков (этап 8) |
| `demo/` | E2E демо-стенд: docker-compose + bootstrap (Greenplum→NiFi→Fuseki+Neo4j+Superset) |
| `docs/` | Теоретические главы, архитектура, методики, экономика, библиография — **не в git** (см. ниже) |
| `docs/examples/` | Примеры RML/YARRRML маппингов и тестовые данные |
| `PLAN.md` | Согласованный исследовательский план (источник истины) |

## Сборка

```bash
./gradlew build              # компиляция + тесты
./gradlew test               # только unit-тесты
./gradlew :nifi-rml-nar:nar  # сборка NAR-bundle
```

NAR-файл появится в `nifi-rml-nar/build/libs/`. Для деплоя — скопировать в `$NIFI_HOME/extensions/` и перезапустить NiFi (или использовать `flows/scripts/run_nifi.sh deploy`).

> **Замечание о среде сборки.** Первый прогон `./gradlew build` тянет зависимости (NiFi 2.8.0, Apache Jena 5.2, Jackson, JsonPath, snakeyaml) с Maven Central. Требуется доступ к `repo.maven.apache.org` или к корпоративному mirror. Если сборка не запускалась в текущей машине, вначале вызовите `./gradlew --refresh-dependencies build`.

## Требования

- **Java 21+** (требование Apache NiFi 2.x).
- **Gradle 8+** (поставляется через `./gradlew`).
- Для режима `MORPH_KGC` дополнительно: **Python ≥ 3.9** и `pip install morph-kgc` на NiFi-хосте.
- Для скриптов NAR-деплоя в `flows/scripts/`: **Docker** + `python3 -m pip install requests`.

## Запуск тестового потока

```bash
./gradlew :nifi-rml-nar:nar
flows/scripts/run_nifi.sh start            # NiFi 2.8.0 в Docker
TOKEN=$(curl -k -s -X POST https://localhost:8443/nifi-api/access/token \
  -d 'username=admin&password=ctsBtRBKHRAx69EqUghvvgEvjnaLjFEB')
python3 flows/scripts/import_blueprint.py \
  --base-url https://localhost:8443/nifi-api \
  --token "$TOKEN" \
  flows/flow_json_to_rdf.json
# Открыть http://localhost:8080/nifi, найти ProcessGroup `rml-json-demo`, запустить.
```

## End-to-End демо-стенд

Полный pipeline для защиты ВКР — `Greenplum → NiFi+RML → (RDF Fuseki + Neo4j + Superset)` — описан в `docs/10_e2e_demo_architecture.md` и поднимается одной командой:

```bash
./gradlew :nifi-rml-nar:nar
docker compose -f docker-compose.demo.yml up -d
bash demo/bootstrap.sh
```

Подробнее: [`demo/README.md`](demo/README.md).

## Бенчмарк

```bash
python evaluation/scripts/generate_datasets.py
ITERATIONS=5 NIFI_TOKEN="$TOKEN" bash evaluation/scripts/run_benchmarks.sh
# Результаты: evaluation/results/results.csv
```

Подробнее в `evaluation/benchmark.md` и `docs/06_evaluation_methodology.md`.

## Ветки git

- **`master`** — финальная версия с поддержкой JSON+CSV+XML, обоих движков (включая AUTO), URL-источника маппингов, потокового режима и YARRRML (через `mapping-format` в основном процессоре).
- **`json-only-parser`** (тег `json-only-parser-complete`) — снимок после этапа 5: JSON-only процессор с обоими движками, без CSV/XML/YARRRML/URL/streaming. Сохранён для иллюстрации фазового подхода в главе 3 ВКР.

## Лицензия

Apache-2.0 (совместимая с экосистемой Apache NiFi и Apache Jena).
