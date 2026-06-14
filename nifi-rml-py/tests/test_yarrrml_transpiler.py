"""Tests for yarrrml_transpiler.transpile()."""
import pytest
import rdflib

from nifi_rml_py_helpers.yarrrml_transpiler import transpile

SCHEMA = "https://schema.org/"
EX_CUSTOMER = "http://example.org/customer/"


def _parse(ttl: str) -> rdflib.Graph:
    g = rdflib.Graph()
    g.parse(data=ttl, format="turtle")
    return g


def test_transpile_returns_string(mappings_dir):
    yaml_content = (mappings_dir / "customers.yarrrml.yml").read_text(encoding="utf-8")
    result = transpile(yaml_content)
    assert isinstance(result, str)
    assert len(result) > 0


def test_transpile_valid_turtle(mappings_dir):
    yaml_content = (mappings_dir / "customers.yarrrml.yml").read_text(encoding="utf-8")
    ttl = transpile(yaml_content)
    # Should parse without errors
    g = _parse(ttl)
    assert len(g) > 0


def test_transpile_contains_required_predicates(mappings_dir):
    yaml_content = (mappings_dir / "customers.yarrrml.yml").read_text(encoding="utf-8")
    ttl = transpile(yaml_content)
    # Check that the resulting RML references schema:name, schema:email
    assert "schema.org" in ttl or "schema:" in ttl
    assert "name" in ttl
    assert "email" in ttl


def test_transpile_roundtrip_parse(mappings_dir, tmp_path, data_dir):
    """Transpiled RML parses without error and contains correct predicates in graph."""
    yaml_content = (mappings_dir / "customers.yarrrml.yml").read_text(encoding="utf-8")
    ttl = transpile(yaml_content)
    g = _parse(ttl)
    # rml:logicalSource triple must be present
    RML = rdflib.Namespace("http://semweb.mmlab.be/ns/rml#")
    sources = list(g.subjects(RML.logicalSource, None))
    assert len(sources) > 0, "Transpiled RML must have at least one rml:logicalSource"
