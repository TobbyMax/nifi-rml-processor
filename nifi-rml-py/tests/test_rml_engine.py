"""Tests for rml_engine.materialize() using fixture customers.json + customers.rml.ttl."""
import pytest
import rdflib
import rdflib.compare

from nifi_rml_py_helpers.constants import InputFormat, OutputFormat
from nifi_rml_py_helpers.rml_engine import materialize


def test_materialize_json_basic(tmp_path, data_dir, mappings_dir, expected_dir):
    graph, count = materialize(
        mapping_path=mappings_dir / "customers.rml.ttl",
        input_path=data_dir / "customers.json",
        input_format=InputFormat.JSON,
        base_iri="http://example.org/",
        work_dir=tmp_path / "morph",
    )
    assert count > 0, "Expected at least one triple"
    assert isinstance(graph, rdflib.Graph)


def test_materialize_json_triples_count(tmp_path, data_dir, mappings_dir):
    # customers.json has 2 records × 3 predicates (rdf:type, schema:name, schema:email) = 6 triples
    graph, count = materialize(
        mapping_path=mappings_dir / "customers.rml.ttl",
        input_path=data_dir / "customers.json",
        input_format=InputFormat.JSON,
        base_iri="http://example.org/",
        work_dir=tmp_path / "morph",
    )
    assert count == 6, f"Expected 6 triples, got {count}"


def test_materialize_json_isomorphic(tmp_path, data_dir, mappings_dir, expected_dir):
    graph, _ = materialize(
        mapping_path=mappings_dir / "customers.rml.ttl",
        input_path=data_dir / "customers.json",
        input_format=InputFormat.JSON,
        base_iri="http://example.org/",
        work_dir=tmp_path / "morph",
    )
    expected = rdflib.Graph()
    expected.parse(str(expected_dir / "customers.ttl"), format="turtle")
    assert rdflib.compare.isomorphic(graph, expected), (
        "Materialized graph is not isomorphic with expected customers.ttl"
    )


def test_materialize_csv(tmp_path, data_dir, mappings_dir):
    graph, count = materialize(
        mapping_path=mappings_dir / "customers.csv.rml.ttl",
        input_path=data_dir / "customers.csv",
        input_format=InputFormat.CSV,
        base_iri="http://example.org/",
        work_dir=tmp_path / "morph",
    )
    assert count > 0
