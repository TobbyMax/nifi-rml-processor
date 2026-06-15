# nifi-rml-processor

Курсовая работа: **«Реализация новых методов автоматической трансформации данных на платформе Apache NiFi с использованием языка RML и YARRRML»**.

Направление: **Бизнес-информатика**.

## Артефакт

Кастомные процессоры **Apache NiFi 2.8.0**, которые нативно исполняют декларативные маппинги [RML](https://rml.io/specs/rml/) и [YARRRML](https://rml.io/yarrrml/spec/) для трансформации **JSON / CSV / XML → RDF** (Turtle / N-Triples / JSON-LD / RDF-XML) внутри потоков данных NiFi.

Репозиторий содержит **две независимые реализации** одного и того же процессора:

- **Java** — `ExecuteRMLMappingProcessor` (модуль `nifi-rml-processors/`) поверх Apache Jena: in-process исполнение, потоковая обработка (Jackson streaming для JSON, ленивый итератор для CSV, StAX-style XPath для XML), эмиссия триплетов через `Jena StreamRDF` без построения полного in-memory графа.
- **Python** — `ExecuteRMLMappingPython` (модуль `nifi-rml-py/`), NiFi 2.x `FlowFileTransform`, вызывает `morph_kgc.materialize()` напрямую (без subprocess-обвязки) и `yatter` для транспиляции YARRRML→RML.

Обе реализации запускаются в одном инстансе NiFi и сравниваются на общих датасетах — см. раздел «Бенчмарк».

Ключевые возможности:

- **Несколько источников маппинга** (`mapping-source`):
  - `INLINE` — текст в свойстве процессора;
  - `FILE` — путь к файлу на диске NiFi-узла;
  - `ATTRIBUTE` — FlowFile-атрибут;
  - `URL` — HTTP(S) / `file://` / `classpath:` URI с TTL-кешем; работает с raw-endpoint'ами GitHub/GitLab, S3, внутренними mapping-репозиториями.
- **Параметризация**: NiFi Expression Language применяется к маппингам и URL — `${tenant.id}`, `${dataset}`, `${region}` подставляются из FlowFile-атрибутов.
- **YARRRML**: встроенная транспиляция YARRRML→RML через свойство `mapping-format` (Java — собственный парсер базового подмножества; Python — `yatter`).
- **Provenance**: на каждый FlowFile пишутся атрибуты `rml.engine.selected`, `rml.triples.count`, `rml.duration.ms`, `rml.input.size.bytes`, `mime.type`, `rml.error.*`. В CSV бенчмарка `rml.engine.selected=RMLMAPPER` соответствует Java-движку, `MORPH_KGC_PY` — Python.

## Состав репозитория

| Раздел | Назначение |
|---|---|
| `nifi-rml-processors/` | Java-модуль с процессорами, engine layer, YARRRML-парсером |
| `nifi-rml-nar/` | Сборочный модуль NAR-bundle для деплоя в NiFi |
| `nifi-rml-py/` | Python-процессор `ExecuteRMLMappingPython` + helper-пакет для venv NiFi |
| `flows/` | Готовые blueprint-описания DataFlow (Java и Python варианты) + скрипты импорта/валидации |
| `evaluation/` | Методика и инфраструктура бенчмарков |
| `demo/lada-example/` | Демо-пример: JSON+CSV+XML источники модели «АвтоВАЗ» (инженер/прайс/конфигурация автомобиля) и YARRRML-маппинги |

## Сборка

```bash
./gradlew build              # компиляция + тесты
./gradlew test               # только unit-тесты
./gradlew :nifi-rml-nar:nar  # сборка NAR-bundle (Java-процессор)
```

NAR-файл появится в `nifi-rml-nar/build/libs/`. Для деплоя — скопировать в `$NIFI_HOME/extensions/` и перезапустить NiFi (или использовать `flows/scripts/run_nifi.sh deploy`).

Python-процессор подхватывается NiFi 2.x автоматически из директории `nifi-rml-py/` (декларация через `ProcessorDetails.dependencies`, `morph-kgc` и `yatter` ставятся в venv NiFi по первому запуску).

> **Замечание о среде сборки.** Первый прогон `./gradlew build` тянет зависимости (NiFi 2.8.0, Apache Jena 5.2, Jackson, JsonPath, snakeyaml) с Maven Central. Требуется доступ к `repo.maven.apache.org`. Если сборка не запускалась в текущей машине, вначале вызовите `./gradlew --refresh-dependencies build`.

## Требования

- **Java 21+** (требование Apache NiFi 2.x).
- **Gradle 8+** (поставляется через `./gradlew`).
- Для Python-процессора: **Python ≥ 3.9**; пакеты `morph-kgc` и `yatter` (объявлены в `ProcessorDetails.dependencies`, ставятся автоматически).
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

Аналогичные blueprint'ы для Python-процессора — `flows/flow_py_json_to_rdf.json`, `flow_py_csv_to_rdf.json`, `flow_py_xml_to_rdf.json`.

## Демо «АвтоВАЗ» (lada-example)

Учебный кейс трансформации разнородных источников одной предметной области в единый RDF-граф:

| Источник | Формат | Описание |
|---|---|---|
| `demo/lada-example/engineer.json` | JSON | Карточки инженеров КБ |
| `demo/lada-example/prices.csv` | CSV | Прайс-лист комплектаций |
| `demo/lada-example/car.xmi` | XML (XMI) | UML/MOF-модель конфигурации автомобиля |

Маппинги в `demo/lada-example/mappings/*.yarrrml.yml`. В `flows/` лежат два blueprint-варианта:

- `flow_lada_separate.json` — три параллельных подпотока (по одному на источник);
- `flow_lada_multi_source.json` — единый поток с маршрутизацией по `mime.type`.

Локальная проверка маппинга без NiFi (через `morph-kgc` напрямую):

```bash
python3 flows/scripts/test_engineer_mapping.py
python3 flows/scripts/test_prices_mapping.py
python3 flows/scripts/test_car_mapping.py
```

## Бенчмарки

Сравнение Java-процессора (`ExecuteRMLMappingProcessor`) и Python-процессора (`ExecuteRMLMappingPython`) на общих датасетах JSON/CSV/XML.

```bash
python evaluation/scripts/generate_datasets.py

# Java-процессор
ITERATIONS=5 NIFI_TOKEN="$TOKEN" \
  bash evaluation/scripts/run_benchmarks.sh --processor java

# Python-процессор
ITERATIONS=5 NIFI_TOKEN="$TOKEN" \
  bash evaluation/scripts/run_benchmarks.sh --processor py

# Результаты: evaluation/results/results.csv
# Колонка engine_selected: RMLMAPPER (Java) vs MORPH_KGC_PY (Python).
```

Поток для Java — `flows/flow_benchmark.json` (PG `rml-benchmark`), для Python — `flows/flow_benchmark_py.json` (PG `rml-benchmark-py`). 

## Лицензия

Apache-2.0 (совместимая с экосистемой Apache NiFi и Apache Jena).
