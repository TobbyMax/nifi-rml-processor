# Тестовые DataFlow

Каталог содержит **5 blueprint-описаний** потоков NiFi 2.x в собственном простом JSON-формате (см. схему ниже) и набор скриптов для импорта и валидации.

| Файл | Сценарий |
|---|---|
| `flow_json_to_rdf.json` | Inline JSON → ExecuteRMLMappingProcessor (RMLMAPPER) → PutFile (Turtle) |
| `flow_csv_to_rdf.json` | GetFile CSV → URL-маппинг с file://-репозитория → PutFile |
| `flow_xml_to_rdf.json` | GetFile XML → XPath-маппинг → JSON-LD |
| `flow_yarrrml.json` | Inline YARRRML → ExecuteYARRRMLMappingProcessor → PutFile |
| `flow_auto_engine_routing.json` | AUTO-режим: маленькие FlowFile → RMLMAPPER, большие → MORPH_KGC, разделение через RouteOnAttribute |

## Схема blueprint

```jsonc
{
  "name": "Имя потока",
  "description": "Описание",
  "processGroupName": "rml-demo",
  "processors": [
    { "id": "alias", "type": "java.class.Name", "name": "Display", "properties": { ... } }
  ],
  "connections": [
    { "from": "alias1", "fromRel": "success", "to": "alias2" }
  ]
}
```

## Скрипты

- `scripts/validate_flow.py` — статически проверяет blueprint(ы): обязательные ключи, уникальность id процессоров, ссылочная целостность connections. Запускается без аргументов — пройдёт по всем `flow_*.json` в каталоге выше.
- `scripts/import_blueprint.py` — отправляет blueprint в живой NiFi 2.x через REST API (`/process-groups/.../processors`, `/connections`). Требует `pip install requests`.
- `scripts/run_nifi.sh` — поднимает локальный NiFi 2.8.0 в Docker с пробросом каталогов NAR/маппингов/данных.

## Пошаговая процедура

```bash
# 1. Собрать NAR-bundle.
./gradlew :nifi-rml-nar:nar

# 2. Запустить NiFi 2.8.0 в Docker.
flows/scripts/run_nifi.sh start

# 3. Дождаться готовности (http://localhost:8080/nifi).

# 4. Получить access token (упрощённо для single-user mode).
TOKEN=$(curl -k -X POST http://localhost:8080/nifi-api/access/token \
  -d 'username=admin&password=Secrets2')

# 5. Импортировать blueprint.
python flows/scripts/import_blueprint.py \
  --base-url http://localhost:8080/nifi-api \
  --token "$TOKEN" \
  flows/flow_json_to_rdf.json

# 6. Перейти в UI, найти ProcessGroup `rml-json-demo`, запустить.
```

## Замечания

- Для AUTO-демо (`flow_auto_engine_routing.json`) положите в `/tmp/nifi-rml-bench` как минимум один маленький JSON (1-100 КБ) и один большой (>5 МБ). Сгенерировать большие можно через `evaluation/scripts/generate_datasets.py`.
- Все потоки используют `temporary-directory=/tmp/nifi-rml`. Этот каталог очищается между перезапусками NiFi.
- URL-маппинги в `flow_csv_to_rdf.json` указывают на `file:///opt/nifi/mappings/...` — соответствие mount'у каталога `docs/examples/mappings/` внутри контейнера.
