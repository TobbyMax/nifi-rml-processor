#!/usr/bin/env python3
"""Аналитические графики: Java (RMLMapper) vs Python (morph-kgc) NiFi processor."""

import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.ticker as ticker
import numpy as np
from pathlib import Path

RESULTS_DIR = Path(__file__).parent.parent / "results"
CHARTS_DIR = RESULTS_DIR / "charts"
CHARTS_DIR.mkdir(exist_ok=True)

JAVA_CSV = RESULTS_DIR / "results_java_20260614_150520.csv"
PY_CSV   = RESULTS_DIR / "results_py_20260614_152514.csv"


DATASET_ORDER = [
    "small_100", "medium_10k", "large_50k", "large_100k", "large_500k", "xlarge_2m",
    "invoices_100", "invoices_1k", "invoices_5k", "invoices_10k",
]

DATASET_LABELS = {
    "small_100":    "100",
    "medium_10k":   "10k",
    "large_50k":    "50k",
    "large_100k":   "100k",
    "large_500k":   "500k",
    "xlarge_2m":    "2M",
    "invoices_100": "100",
    "invoices_1k":  "1k",
    "invoices_5k":  "5k",
    "invoices_10k": "10k",
}

COLORS = {
    "java":    "#4C72B0",
    "python":  "#DD8452",
    "rml":     "#55A868",
    "yarrrml": "#C44E52",
    "TURTLE":  "#8172B2",
    "JSONLD":  "#937860",
    "json":    "#64B5CD",
    "csv":     "#F4A582",
    "xml":     "#A6CEE3",
}

PROC_RU = {
    "Java (RMLMapper)":  "Java (RMLMapper)",
    "Python (morph-kgc)": "Python (morph-kgc)",
}

plt.rcParams.update({
    "figure.dpi": 150,
    "font.size": 10,
    "axes.spines.top": False,
    "axes.spines.right": False,
    "axes.grid": True,
    "axes.grid.axis": "y",
    "grid.alpha": 0.4,
    "grid.linestyle": "--",
})


def load_data():
    java = pd.read_csv(JAVA_CSV)
    py   = pd.read_csv(PY_CSV)
    java["processor"] = "Java (RMLMapper)"
    py["processor"]   = "Python (morph-kgc)"
    df = pd.concat([java, py], ignore_index=True)
    df["dataset_base"] = df["dataset"].str.replace(r"\.(json|csv|xml)$", "", regex=True)
    return df


def mean_excl_timeout(series):
    return series.mean() if len(series) > 0 else np.nan


def avg_by(df, *group_cols):
    return df.groupby(list(group_cols))["duration_ms"].agg(mean_excl_timeout).reset_index()


# ── График 1: Java vs Python — общая производительность по датасетам ─────────
def chart_java_vs_python_overall(df):
    fig, ax = plt.subplots(figsize=(11, 5))

    agg = avg_by(df, "processor", "dataset_base")
    pivot = agg.pivot(index="dataset_base", columns="processor", values="duration_ms")

    order = [d for d in DATASET_ORDER if d in pivot.index]
    pivot = pivot.reindex(order)
    labels = [DATASET_LABELS.get(d, d) for d in order]

    x = np.arange(len(pivot))
    w = 0.35
    ax.bar(x - w / 2, pivot["Java (RMLMapper)"],  w, label="Java (RMLMapper)",  color=COLORS["java"])
    ax.bar(x + w / 2, pivot["Python (morph-kgc)"], w, label="Python (morph-kgc)", color=COLORS["python"])

    ax.set_xticks(x)
    ax.set_xticklabels(labels)
    ax.set_xlabel("Размер датасета (записей)")
    ax.set_ylabel("Среднее время выполнения, мс")
    ax.set_title("Java vs Python — общая производительность по размеру датасета")
    ax.legend()
    fig.tight_layout()
    fig.savefig(CHARTS_DIR / "01_java_vs_python_overall.png")
    plt.close(fig)
    print("Сохранён 01_java_vs_python_overall.png")


# ── График 2: Java vs Python — логарифмическая шкала (JSON→TURTLE, RML) ──────
def chart_java_vs_python_logscale(df):
    sub = df[(df["input_format"] == "json") & (df["mapping_type"] == "rml") & (df["output_format"] == "TURTLE")]
    agg = avg_by(sub, "processor", "dataset_base")

    order = [d for d in ["small_100", "medium_10k", "large_50k", "large_100k"] if d in agg["dataset_base"].unique()]
    labels = [DATASET_LABELS.get(d, d) for d in order]

    fig, ax = plt.subplots(figsize=(9, 5))
    for proc, color in [("Java (RMLMapper)", COLORS["java"]), ("Python (morph-kgc)", COLORS["python"])]:
        sub2 = agg[agg["processor"] == proc].set_index("dataset_base").reindex(order)
        ax.plot(labels, sub2["duration_ms"], marker="o", label=proc, color=color, linewidth=2)

    ax.set_yscale("log")
    ax.yaxis.set_major_formatter(ticker.FuncFormatter(lambda v, _: f"{v:,.0f}"))
    ax.set_xlabel("Размер датасета (записей)")
    ax.set_ylabel("Среднее время, мс  (лог. шкала)")
    ax.set_title("Java vs Python — JSON→TURTLE, RML-маппинг (логарифмическая шкала)")
    ax.legend()
    fig.tight_layout()
    fig.savefig(CHARTS_DIR / "02_java_vs_python_logscale.png")
    plt.close(fig)
    print("Сохранён 02_java_vs_python_logscale.png")


# ── График 3: RML vs YARRRML — по процессорам ────────────────────────────────
def chart_mapping_type(df):
    sub = df[(df["input_format"] == "json") & (df["output_format"] == "TURTLE")]
    agg = avg_by(sub, "processor", "mapping_type", "dataset_base")

    processors = ["Java (RMLMapper)", "Python (morph-kgc)"]
    fig, axes = plt.subplots(1, 2, figsize=(13, 5), sharey=False)

    order = [d for d in ["small_100", "medium_10k", "large_50k", "large_100k"] if d in agg["dataset_base"].unique()]
    labels = [DATASET_LABELS.get(d, d) for d in order]

    for ax, proc in zip(axes, processors):
        sub2 = agg[agg["processor"] == proc]
        for mtype, color in [("rml", COLORS["rml"]), ("yarrrml", COLORS["yarrrml"])]:
            row = sub2[sub2["mapping_type"] == mtype].set_index("dataset_base").reindex(order)
            ax.plot(labels, row["duration_ms"], marker="o", label=mtype.upper(), color=color, linewidth=2)
        ax.set_title(proc)
        ax.set_xlabel("Размер датасета (записей)")
        ax.set_ylabel("Среднее время, мс")
        ax.legend()

    fig.suptitle("Тип маппинга: RML vs YARRRML — вход JSON, выход TURTLE", fontweight="bold")
    fig.tight_layout()
    fig.savefig(CHARTS_DIR / "03_rml_vs_yarrrml.png")
    plt.close(fig)
    print("Сохранён 03_rml_vs_yarrrml.png")


# ── График 4: Формат вывода TURTLE vs JSON-LD ────────────────────────────────
def chart_output_format(df):
    sub = df[(df["input_format"] == "json") & (df["mapping_type"] == "rml")]
    agg = avg_by(sub, "processor", "output_format", "dataset_base")

    processors = ["Java (RMLMapper)", "Python (morph-kgc)"]
    fig, axes = plt.subplots(1, 2, figsize=(13, 5), sharey=False)

    order = [d for d in ["small_100", "medium_10k", "large_50k", "large_100k"] if d in agg["dataset_base"].unique()]
    labels = [DATASET_LABELS.get(d, d) for d in order]

    for ax, proc in zip(axes, processors):
        sub2 = agg[agg["processor"] == proc]
        for fmt, color in [("TURTLE", COLORS["TURTLE"]), ("JSONLD", COLORS["JSONLD"])]:
            row = sub2[sub2["output_format"] == fmt].set_index("dataset_base").reindex(order)
            ax.plot(labels, row["duration_ms"], marker="s", label=fmt, color=color, linewidth=2)
        ax.set_title(proc)
        ax.set_xlabel("Размер датасета (записей)")
        ax.set_ylabel("Среднее время, мс")
        ax.legend()

    fig.suptitle("Формат вывода: TURTLE vs JSON-LD — вход JSON, RML-маппинг", fontweight="bold")
    fig.tight_layout()
    fig.savefig(CHARTS_DIR / "04_output_format_turtle_vs_jsonld.png")
    plt.close(fig)
    print("Сохранён 04_output_format_turtle_vs_jsonld.png")


# ── График 5: Формат входных данных JSON / CSV / XML ─────────────────────────
def chart_input_format(df):
    sub = df[(df["output_format"] == "TURTLE") & (df["mapping_type"] == "rml")]
    agg = avg_by(sub, "processor", "input_format", "dataset_base")

    processors = ["Java (RMLMapper)", "Python (morph-kgc)"]
    fig, axes = plt.subplots(1, 2, figsize=(14, 5), sharey=False)

    json_order = [d for d in ["small_100", "medium_10k", "large_50k", "large_100k"] if d in agg["dataset_base"].unique()]
    xml_order  = [d for d in ["invoices_100", "invoices_1k", "invoices_5k", "invoices_10k"] if d in agg["dataset_base"].unique()]

    # числовые позиции: 0-3 для json/csv, 5-8 для xml (зазор = 1)
    json_x = list(range(len(json_order)))
    xml_x  = list(range(len(json_order) + 1, len(json_order) + 1 + len(xml_order)))
    all_x      = json_x + xml_x
    all_labels = [DATASET_LABELS.get(d, d) for d in json_order + xml_order]

    for ax, proc in zip(axes, processors):
        sub2 = agg[agg["processor"] == proc]
        for fmt, color, order, x_pos in [
            ("json", COLORS["json"], json_order, json_x),
            ("csv",  COLORS["csv"],  json_order, json_x),
            ("xml",  COLORS["xml"],  xml_order,  xml_x),
        ]:
            row = sub2[sub2["input_format"] == fmt].set_index("dataset_base").reindex(order)
            ax.plot(x_pos, row["duration_ms"], marker="D", label=fmt.upper(), color=color, linewidth=2)
        ax.axvline(len(json_order) - 0.5, color="gray", linestyle=":", linewidth=1, alpha=0.6)
        ax.set_yscale("log")
        ax.yaxis.set_major_formatter(ticker.FuncFormatter(lambda v, _: f"{v:,.0f}"))
        ax.set_xticks(all_x)
        ax.set_xticklabels(all_labels)
        ax.set_title(proc)
        ax.set_xlabel("Размер датасета  [JSON/CSV · · XML]")
        ax.set_ylabel("Среднее время, мс  (лог. шкала)")
        ax.legend()

    fig.suptitle("Сравнение форматов входных данных: JSON / CSV / XML — выход TURTLE, RML-маппинг", fontweight="bold")
    fig.tight_layout()
    fig.savefig(CHARTS_DIR / "05_input_format_comparison.png")
    plt.close(fig)
    print("Сохранён 05_input_format_comparison.png")


# ── График 6: Коэффициент ускорения Java над Python ──────────────────────────
def chart_speedup(df):
    sub = df[(df["input_format"] == "json") & (df["mapping_type"] == "rml") & (df["output_format"] == "TURTLE")]
    agg = avg_by(sub, "processor", "dataset_base")
    pivot = agg.pivot(index="dataset_base", columns="processor", values="duration_ms")

    order = [d for d in ["small_100", "medium_10k", "large_50k", "large_100k"] if d in pivot.index]
    pivot = pivot.reindex(order)
    labels = [DATASET_LABELS.get(d, d) for d in order]

    speedup = pivot["Python (morph-kgc)"] / pivot["Java (RMLMapper)"]

    fig, ax = plt.subplots(figsize=(9, 4))
    bars = ax.bar(labels, speedup, color=[COLORS["java"] if v >= 1 else COLORS["python"] for v in speedup])
    ax.axhline(1.0, color="black", linewidth=1, linestyle="--", label="Одинаковая производительность")
    for bar, val in zip(bars, speedup):
        if not np.isnan(val):
            ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.05,
                    f"{val:.1f}×", ha="center", va="bottom", fontsize=9)
    ax.set_xlabel("Размер датасета (записей)")
    ax.set_ylabel("Время Python / Время Java  (>1 — Java быстрее)")
    ax.set_title("Коэффициент ускорения: время Python ÷ время Java  (JSON→TURTLE, RML)\n>1 означает, что Java быстрее")
    ax.legend()
    fig.tight_layout()
    fig.savefig(CHARTS_DIR / "06_speedup_ratio.png")
    plt.close(fig)
    print("Сохранён 06_speedup_ratio.png")


# ── График 7: Пропускная способность (триплей/сек) ───────────────────────────
def chart_throughput(df):
    sub = df[(df["input_format"] == "json") & (df["mapping_type"] == "rml") &
             (df["output_format"] == "TURTLE") & (df["triples"] > 0)].copy()
    sub["triples_per_sec"] = sub["triples"] / (sub["duration_ms"] / 1000)

    agg = sub.groupby(["processor", "dataset_base"])["triples_per_sec"].mean().reset_index()
    pivot = agg.pivot(index="dataset_base", columns="processor", values="triples_per_sec")

    order = [d for d in ["small_100", "medium_10k", "large_50k", "large_100k"] if d in pivot.index]
    pivot = pivot.reindex(order)
    labels = [DATASET_LABELS.get(d, d) for d in order]

    x = np.arange(len(pivot))
    w = 0.35
    fig, ax = plt.subplots(figsize=(10, 5))
    ax.bar(x - w / 2, pivot.get("Java (RMLMapper)",  0), w, label="Java (RMLMapper)",  color=COLORS["java"])
    ax.bar(x + w / 2, pivot.get("Python (morph-kgc)", 0), w, label="Python (morph-kgc)", color=COLORS["python"])
    ax.set_xticks(x)
    ax.set_xticklabels(labels)
    ax.set_xlabel("Размер датасета (записей)")
    ax.set_ylabel("Средняя пропускная способность, трипл/сек")
    ax.set_title("Пропускная способность: трипл в секунду — вход JSON, выход TURTLE, RML-маппинг")
    ax.yaxis.set_major_formatter(ticker.FuncFormatter(lambda v, _: f"{v:,.0f}"))
    ax.legend()
    fig.tight_layout()
    fig.savefig(CHARTS_DIR / "07_throughput_triples_per_sec.png")
    plt.close(fig)
    print("Сохранён 07_throughput_triples_per_sec.png")


# ── График 8: XML — время выполнения от размера датасета ─────────────────────
def chart_xml_line(df):
    XML_ORDER  = ["invoices_100", "invoices_1k", "invoices_5k", "invoices_10k"]
    XML_LABELS = {"invoices_100": "100", "invoices_1k": "1k", "invoices_5k": "5k", "invoices_10k": "10k"}

    sub = df[df["input_format"] == "xml"].copy()

    fig, ax = plt.subplots(figsize=(9, 5))

    for proc, color, marker in [
        ("Java (RMLMapper)",  COLORS["java"],   "o"),
        ("Python (morph-kgc)", COLORS["python"], "s"),
    ]:
        psub = sub[sub["processor"] == proc]
        agg  = psub.groupby("dataset_base")["duration_ms"].agg(mean_excl_timeout).reindex(XML_ORDER)
        labels = [XML_LABELS[d] for d in XML_ORDER]
        ax.plot(labels, agg.values, marker=marker, label=proc, color=color, linewidth=2.5, markersize=8)

        for x_val, y_val in zip(labels, agg.values):
            if not np.isnan(y_val):
                ax.annotate(f"{y_val:,.0f} мс", (x_val, y_val),
                            textcoords="offset points", xytext=(0, 10),
                            ha="center", fontsize=8, color=color)

    ax.set_xlabel("Размер датасета (количество накладных)")
    ax.set_ylabel("Среднее время выполнения, мс")
    ax.set_title("XML — время выполнения от размера датасета\nJava (RMLMapper) vs Python (morph-kgc), выход TURTLE, RML-маппинг")
    ax.legend()
    fig.tight_layout()
    fig.savefig(CHARTS_DIR / "08_xml_line_java_vs_python.png")
    plt.close(fig)
    print("Сохранён 08_xml_line_java_vs_python.png")


if __name__ == "__main__":
    df = load_data()
    chart_java_vs_python_overall(df)
    chart_java_vs_python_logscale(df)
    chart_mapping_type(df)
    chart_output_format(df)
    chart_input_format(df)
    chart_speedup(df)
    chart_throughput(df)
    chart_xml_line(df)
    print(f"\nВсе графики сохранены в: {CHARTS_DIR}")
