"""
NiFi 2.x Python processor: ExecuteRMLMappingPython
Executes RML/YARRRML mappings via morph_kgc.materialize() (library call, no subprocess).

All helper logic is inlined so this file is self-contained in the NiFi venv.
The nifi_rml_py_helpers package is used only for unit tests outside NiFi.
"""
import io
import re
import shutil
import time
import uuid
from enum import Enum
from pathlib import Path
from urllib.parse import urlparse

from nifiapi.flowfiletransform import FlowFileTransform, FlowFileTransformResult
from nifiapi.properties import (
    ExpressionLanguageScope,
    PropertyDescriptor,
    StandardValidators,
)

# ---------------------------------------------------------------------------
# Inlined helpers (mirrors nifi_rml_py_helpers/)
# ---------------------------------------------------------------------------

class _InputFormat(Enum):
    JSON = ("application/json", "json")
    CSV = ("text/csv", "csv")
    XML = ("application/xml", "xml")

    def __init__(self, mime_type, file_extension):
        self.mime_type = mime_type
        self.file_extension = file_extension

    @classmethod
    def from_string(cls, value):
        return cls[value.strip().upper()]


class _OutputFormat(Enum):
    TURTLE = ("text/turtle", "turtle", "ttl")
    NTRIPLES = ("application/n-triples", "nt", "nt")
    JSONLD = ("application/ld+json", "json-ld", "jsonld")
    RDFXML = ("application/rdf+xml", "xml", "rdf")

    def __init__(self, mime_type, rdflib_format, file_extension):
        self.mime_type = mime_type
        self.rdflib_format = rdflib_format
        self.file_extension = file_extension

    @classmethod
    def from_string(cls, value):
        normalized = value.strip().upper().replace("-", "").replace("/", "")
        return cls[normalized]


def _materialize(mapping_path, input_path, input_format, base_iri, work_dir):
    """Run morph_kgc.materialize() and return (graph, triples_count)."""
    import morph_kgc

    work_dir.mkdir(parents=True, exist_ok=True)

    staged_input = work_dir / f"input.{input_format.file_extension}"
    staged_mapping = work_dir / "morph-mapping.ttl"

    shutil.copy2(input_path, staged_input)
    mapping_content = mapping_path.read_text(encoding="utf-8")
    # Handle both single and double quotes in rml:source
    rewritten = re.sub(
        r"rml:source\s+['\"][^'\"]+['\"]",
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

    return graph, len(graph)


def _transpile(yaml_str):
    """Transpile YARRRML (YAML string) to RML/Turtle via yatter."""
    import yaml
    import yatter

    yarrrml_dict = yaml.safe_load(yaml_str)
    result = yatter.translate(yarrrml_dict)
    if result is None:
        raise ValueError("yatter failed to transpile YARRRML mapping")
    return result


def _serialize(graph, output_format):
    """Serialize an rdflib Graph to bytes in the requested format."""
    buf = io.BytesIO()
    graph.serialize(destination=buf, format=output_format.rdflib_format)
    return buf.getvalue()


class _MappingRepository:
    """TTL-cache for RML mapping documents fetched by URL."""

    def __init__(self, cache_ttl_seconds=600):
        self._ttl = cache_ttl_seconds
        self._cache = {}

    def fetch(self, url):
        import time as _time

        if not url or not url.strip():
            raise ValueError("Mapping URL is empty")
        now = _time.monotonic()
        entry = self._cache.get(url)
        if entry and (now - entry[1]) < self._ttl:
            return entry[0]
        body = self._fetch_uncached(url)
        self._cache[url] = (body, now)
        return body

    def _fetch_uncached(self, url):
        import requests

        parsed = urlparse(url)
        scheme = parsed.scheme.lower()
        if scheme in ("http", "https"):
            resp = requests.get(url, timeout=30)
            resp.raise_for_status()
            return resp.text
        if scheme == "file":
            return Path(parsed.path).read_text(encoding="utf-8")
        raise ValueError(f"Unsupported mapping URL scheme: {scheme}")

    def clear(self):
        self._cache.clear()

    def close(self):
        self._cache.clear()


# ---------------------------------------------------------------------------
# NiFi processor
# ---------------------------------------------------------------------------

class ExecuteRMLMappingPython(FlowFileTransform):

    class Java:
        implements = ["org.apache.nifi.python.processor.FlowFileTransform"]

    class ProcessorDetails:
        version = "0.1.0"
        tags = ["rml", "rdf", "python", "morph-kgc", "semantic", "knowledge graph"]
        description = (
            "Executes an RML mapping against incoming FlowFile content using "
            "morph_kgc.materialize() (in-process, no subprocess). "
            "Supports RML/Turtle and YARRRML mapping formats."
        )
        dependencies = [
            "morph-kgc>=2.7",
            "rdflib>=7.0",
            "yatter>=1.2",
            "requests>=2.31",
            "pyyaml>=6.0",
        ]

    # NiFi 2.x Python API PropertyDescriptor parameters:
    # name, description, default_value, required, allowable_values, validators, expression_language_scope
    # Примечание: validators и expression_language_scope не указываются явно,
    # т.к. NiFi AST parser не может разрешить атрибуты из импортированных модулей
    MAPPING_SOURCE = PropertyDescriptor(
        name="mapping-source",
        description="Where to read the RML mapping document from",
        default_value="INLINE",
        required=True,
        allowable_values=["INLINE", "FILE", "ATTRIBUTE", "URL"],
    )
    MAPPING_CONTENT = PropertyDescriptor(
        name="mapping-content",
        description="RML mapping document in Turtle. Required when mapping source is INLINE.",
        required=False,
    )
    MAPPING_FILE = PropertyDescriptor(
        name="mapping-file",
        description="Path to a Turtle file with the RML mapping. Required when mapping source is FILE.",
        required=False,
    )
    MAPPING_ATTRIBUTE = PropertyDescriptor(
        name="mapping-attribute",
        description="Name of the FlowFile attribute carrying the RML mapping. Required when mapping source is ATTRIBUTE.",
        default_value="rml.mapping",
        required=False,
    )
    MAPPING_URL = PropertyDescriptor(
        name="mapping-url",
        description="URL of the mapping document. Supports http(s)://, file:// schemes.",
        required=False,
    )
    MAPPING_FORMAT = PropertyDescriptor(
        name="mapping-format",
        description="Format of the mapping document. YARRRML is transpiled to RML/Turtle before execution.",
        default_value="RML_TTL",
        required=True,
        allowable_values=["RML_TTL", "YARRRML"],
    )
    MAPPING_CACHE_TTL_SECONDS = PropertyDescriptor(
        name="mapping-cache-ttl-seconds",
        description="How long to cache mappings fetched from URL before re-fetching. 0 disables caching.",
        default_value="600",
        required=True,
    )
    INPUT_DATA_FORMAT = PropertyDescriptor(
        name="input-data-format",
        description="Format of the FlowFile content",
        default_value="JSON",
        required=True,
        allowable_values=["JSON", "CSV", "XML"],
    )
    OUTPUT_RDF_FORMAT = PropertyDescriptor(
        name="output-rdf-format",
        description="Output RDF format",
        default_value="TURTLE",
        required=True,
        allowable_values=["TURTLE", "NTRIPLES", "JSONLD", "RDFXML"],
    )
    BASE_IRI = PropertyDescriptor(
        name="base-iri",
        description="Base IRI for relative references in the mapping",
        default_value="http://example.org/",
        required=True,
    )
    TEMPORARY_DIRECTORY = PropertyDescriptor(
        name="temporary-directory",
        description="Directory for temporary files used during mapping execution",
        default_value="/tmp/nifi-rml-py",
        required=True,
    )

    property_descriptors = [
        MAPPING_SOURCE,
        MAPPING_CONTENT,
        MAPPING_FILE,
        MAPPING_ATTRIBUTE,
        MAPPING_URL,
        MAPPING_FORMAT,
        MAPPING_CACHE_TTL_SECONDS,
        INPUT_DATA_FORMAT,
        OUTPUT_RDF_FORMAT,
        BASE_IRI,
        TEMPORARY_DIRECTORY,
    ]

    def getPropertyDescriptors(self):
        """Returns the list of property descriptors for this processor."""
        return [
            self.MAPPING_SOURCE,
            self.MAPPING_CONTENT,
            self.MAPPING_FILE,
            self.MAPPING_ATTRIBUTE,
            self.MAPPING_URL,
            self.MAPPING_FORMAT,
            self.MAPPING_CACHE_TTL_SECONDS,
            self.INPUT_DATA_FORMAT,
            self.OUTPUT_RDF_FORMAT,
            self.BASE_IRI,
            self.TEMPORARY_DIRECTORY,
        ]

    def __init__(self, **kwargs):
        # NiFi может передавать дополнительные аргументы (например, 'jvm'),
        # но базовый класс FlowFileTransform их не принимает
        super().__init__()
        self._repo = None

    def onScheduled(self, context):
        ttl = int(context.getProperty(self.MAPPING_CACHE_TTL_SECONDS).getValue() or "600")
        self._repo = _MappingRepository(cache_ttl_seconds=ttl)
        tmp = context.getProperty(self.TEMPORARY_DIRECTORY).getValue()
        Path(tmp).mkdir(parents=True, exist_ok=True)
        self.logger.info(f"ExecuteRMLMappingPython scheduled; tmp={tmp} ttl={ttl}s")

    def onStopped(self, context):
        if self._repo:
            self._repo.close()
            self._repo = None

    def transform(self, context, flowfile):
        start = time.monotonic()

        tmp_dir = Path(context.getProperty(self.TEMPORARY_DIRECTORY).getValue())
        run_dir = tmp_dir / str(uuid.uuid4())
        run_dir.mkdir(parents=True, exist_ok=True)

        try:
            input_format = _InputFormat.from_string(
                context.getProperty(self.INPUT_DATA_FORMAT).getValue()
            )
            output_format = _OutputFormat.from_string(
                context.getProperty(self.OUTPUT_RDF_FORMAT).getValue()
            )
            base_iri = context.getProperty(self.BASE_IRI).getValue()

            input_bytes = flowfile.getContentsAsBytes()
            input_size = len(input_bytes)
            tmp_input = run_dir / f"input.{input_format.file_extension}"
            tmp_input.write_bytes(input_bytes)

            mapping_content = self._read_mapping(context, flowfile)

            if context.getProperty(self.MAPPING_FORMAT).getValue() == "YARRRML":
                mapping_content = _transpile(mapping_content)

            tmp_mapping = run_dir / "mapping.ttl"
            tmp_mapping.write_text(mapping_content, encoding="utf-8")

            graph, triples_count = _materialize(
                mapping_path=tmp_mapping,
                input_path=tmp_input,
                input_format=input_format,
                base_iri=base_iri,
                work_dir=run_dir / "morph",
            )

            rdf_bytes = _serialize(graph, output_format)
            duration_ms = int((time.monotonic() - start) * 1000)

            original_filename = flowfile.getAttribute("filename") or "output"
            stem = original_filename.rsplit(".", 1)[0] if "." in original_filename else original_filename
            new_filename = f"{stem}.{output_format.file_extension}"

            attributes = {
                "rml.engine.selected": "MORPH_KGC_PY",
                "rml.input.size.bytes": str(input_size),
                "rml.output.format": output_format.name,
                "rml.triples.count": str(triples_count),
                "rml.duration.ms": str(duration_ms),
                "mime.type": output_format.mime_type,
                "filename": new_filename,
            }
            return FlowFileTransformResult(
                relationship="success",
                contents=rdf_bytes,
                attributes=attributes,
            )

        except Exception as e:
            import traceback
            self.logger.error(f"ExecuteRMLMappingPython failed: {str(e)}")
            self.logger.error(traceback.format_exc())
            return FlowFileTransformResult(
                relationship="failure",
                attributes={
                    "rml.error.message": str(e),
                    "rml.error.type": type(e).__name__,
                },
            )
        finally:
            shutil.rmtree(run_dir, ignore_errors=True)

    def _read_mapping(self, context, flowfile):
        source = context.getProperty(self.MAPPING_SOURCE).getValue()

        if source == "INLINE":
            value = context.getProperty(self.MAPPING_CONTENT).evaluateAttributeExpressions(flowfile).getValue()
            if not value:
                raise ValueError("mapping-content is empty")
            return value

        if source == "FILE":
            path_val = context.getProperty(self.MAPPING_FILE).evaluateAttributeExpressions(flowfile).getValue()
            if not path_val:
                raise ValueError("mapping-file is empty")
            return Path(path_val).read_text(encoding="utf-8")

        if source == "ATTRIBUTE":
            attr_name = context.getProperty(self.MAPPING_ATTRIBUTE).getValue()
            value = flowfile.getAttribute(attr_name)
            if not value:
                raise ValueError(f"Mapping attribute '{attr_name}' is missing or empty")
            return value

        if source == "URL":
            url = context.getProperty(self.MAPPING_URL).evaluateAttributeExpressions(flowfile).getValue()
            if not url:
                raise ValueError("mapping-url is empty")
            return self._repo.fetch(url)

        raise ValueError(f"Unknown mapping source: {source}")
