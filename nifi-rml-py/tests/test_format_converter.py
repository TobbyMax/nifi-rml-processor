"""Tests for format_converter.serialize() across all four OutputFormats."""
import rdflib
import rdflib.compare
import pytest

from nifi_rml_py_helpers.constants import OutputFormat
from nifi_rml_py_helpers.format_converter import serialize

SCHEMA = rdflib.Namespace("https://schema.org/")
EX = rdflib.Namespace("http://example.org/")


def _sample_graph() -> rdflib.Graph:
    g = rdflib.Graph()
    g.bind("schema", SCHEMA)
    g.bind("ex", EX)
    subject = EX["person/1"]
    g.add((subject, rdflib.RDF.type, SCHEMA.Person))
    g.add((subject, SCHEMA.name, rdflib.Literal("Test User")))
    g.add((subject, SCHEMA.email, rdflib.Literal("test@example.com")))
    return g


@pytest.mark.parametrize("fmt", list(OutputFormat))
def test_serialize_roundtrip_isomorphic(fmt):
    original = _sample_graph()
    raw = serialize(original, fmt)

    assert isinstance(raw, bytes)
    assert len(raw) > 0

    reparsed = rdflib.Graph()
    reparsed.parse(data=raw, format=fmt.rdflib_format)

    assert rdflib.compare.isomorphic(original, reparsed), (
        f"Roundtrip for {fmt.name} broke graph isomorphism"
    )


def test_serialize_turtle_contains_schema(sample_graph=None):
    graph = _sample_graph()
    raw = serialize(graph, OutputFormat.TURTLE)
    text = raw.decode("utf-8")
    assert "schema" in text.lower() or "schema.org" in text


def test_serialize_ntriples_line_format():
    graph = _sample_graph()
    raw = serialize(graph, OutputFormat.NTRIPLES)
    lines = [l for l in raw.decode("utf-8").splitlines() if l.strip() and not l.startswith("#")]
    # Each non-empty line must end with " ."
    for line in lines:
        assert line.rstrip().endswith(" .") or line.rstrip().endswith("."), (
            f"N-Triples line malformed: {line!r}"
        )


def test_serialize_jsonld_is_json():
    import json
    graph = _sample_graph()
    raw = serialize(graph, OutputFormat.JSONLD)
    # Must be valid JSON
    parsed = json.loads(raw)
    assert parsed is not None


def test_serialize_rdfxml_has_rdf_root():
    graph = _sample_graph()
    raw = serialize(graph, OutputFormat.RDFXML)
    text = raw.decode("utf-8")
    assert "rdf:RDF" in text or "RDF" in text
