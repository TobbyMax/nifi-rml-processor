import re
import shutil
import tempfile
from pathlib import Path

import morph_kgc
import rdflib

from .constants import InputFormat, OutputFormat


def materialize(
    mapping_path: Path,
    input_path: Path,
    input_format: InputFormat,
    base_iri: str,
    work_dir: Path,
) -> tuple[rdflib.Graph, int]:
    """Run morph_kgc.materialize() and return (graph, triples_count)."""
    work_dir.mkdir(parents=True, exist_ok=True)

    input_filename = f"input.{input_format.file_extension}"
    staged_input = work_dir / input_filename
    staged_mapping = work_dir / "morph-mapping.ttl"

    shutil.copy2(input_path, staged_input)
    mapping_content = mapping_path.read_text(encoding="utf-8")
    # Point rml:source at the absolute staged file path so morph-kgc
    # can find it regardless of the calling process's CWD.
    rewritten = re.sub(
        r'rml:source\s+"[^"]+"',
        f'rml:source "{staged_input.resolve()}"',
        mapping_content,
    )
    staged_mapping.write_text(rewritten, encoding="utf-8")

    config = f"""
[CONFIGURATION]
output_format=N-TRIPLES

[datasource]
mappings={staged_mapping.resolve()}
"""
    try:
        graph = morph_kgc.materialize(config)
    finally:
        staged_input.unlink(missing_ok=True)
        staged_mapping.unlink(missing_ok=True)

    triples_count = len(graph)
    return graph, triples_count
