# End-to-End demo стенд

Минимальный docker-compose стенд для демонстрации полного видения продукта:
**Greenplum → Apache NiFi (с RML-процессором) → RDF Fuseki + Neo4j + Superset**.

Полный архитектурный документ: [`docs/10_e2e_demo_architecture.md`](../docs/10_e2e_demo_architecture.md).

## Запуск

```bash
# 1. Собрать NAR-bundle
./gradlew :nifi-rml-nar:nar

# 2. Поднять стенд (Postgres вместо Greenplum, NiFi 2.8.0, Fuseki, Neo4j, Superset)
docker compose -f docker-compose.demo.yml up -d

# 3. Накатить схему, данные, маппинги, индексы Neo4j, dataset'ы Fuseki
bash demo/bootstrap.sh

# 4. Открыть UI:
#    NiFi      http://localhost:8080/nifi   (admin / Secrets4ChangeMeNow)
#    Fuseki    http://localhost:3030        (admin / admin)
#    Neo4j     http://localhost:7474        (neo4j / Secrets4ChangeMeNow)
#    Superset  http://localhost:8089        (admin / admin)
```

## Опционально: GraphRAG-агент

```bash
export OPENAI_API_KEY=sk-...
docker compose -f docker-compose.demo.yml --profile ai up -d graphrag-agent
# Streamlit-чат: http://localhost:8501
```

## NiFi-flow `flow_e2e_demo.json`

Process Group `gp-to-knowledge-graph` собирается в UI NiFi после первого запуска
(пошаговая инструкция — `docs/10_e2e_demo_architecture.md`, §10.4) и экспортируется
через `Download flow definition` в `flows/flow_e2e_demo.json`. После этого
повторный запуск `bash demo/bootstrap.sh` автоматически импортирует его в новый
NiFi через REST.

Альтернатива — тот же flow можно собрать программно через REST API скриптом
(будет добавлен как `demo/build_e2e_flow.py` после смок-теста стенда).

## Что внутри

| Файл / директория | Назначение |
|---|---|
| `docker-compose.demo.yml` (в корне) | сервисы: greenplum/postgres, mapping-repo, nifi, fuseki, neo4j, superset |
| `demo/bootstrap.sh` | идемпотентный pipeline-bootstrap (датасет, mapping-репо, индексы, импорт flow) |
| `demo/greenplum-init/` | DDL и seed для Postgres-инициализации (применяется автоматически) |
| `demo/mappings/{acme,globex}/` | репозиторий RML-маппингов на nginx, multi-tenant раскладка |
| `demo/superset/bootstrap.sh` | инициализация админ-пользователя Superset |
| `demo/graphrag-agent/` | (опц.) Streamlit + LangChain + Neo4j — natural-language → Cypher |

## Лимиты

- **Greenplum заменён на PostgreSQL 16** для портативности на ноутбуке.
  Distribution-clauses в DDL закомментированы; для production-стенда они раскомментируются.
- **Neo4j Community** (без n10s-multi-database). Tenant различается префиксом ID,
  не отдельной db.
- **Объём данных в demo-режиме — 1k customers / 10k orders.** Полный бенчмарк
  (до 1M записей) — отдельный сценарий из `evaluation/scripts/run_benchmarks.sh`.
- **Single-user NiFi и дефолтные пароли** — только для демо.
