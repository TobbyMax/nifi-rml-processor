"""Tests for MappingRepository: file://, http, TTL invalidation."""
import time
from pathlib import Path

import pytest

from nifi_rml_py_helpers.mapping_repository import MappingRepository


def test_fetch_file_url(mappings_dir):
    repo = MappingRepository(cache_ttl_seconds=60)
    url = (mappings_dir / "customers.rml.ttl").as_uri()
    content = repo.fetch(url)
    assert "rml:logicalSource" in content


def test_fetch_file_url_cached(mappings_dir):
    repo = MappingRepository(cache_ttl_seconds=60)
    url = (mappings_dir / "customers.rml.ttl").as_uri()
    content1 = repo.fetch(url)
    content2 = repo.fetch(url)
    assert content1 == content2


def test_ttl_expiry(mappings_dir):
    repo = MappingRepository(cache_ttl_seconds=0)
    url = (mappings_dir / "customers.rml.ttl").as_uri()
    content1 = repo.fetch(url)
    # TTL=0 means re-fetch every time
    content2 = repo.fetch(url)
    assert content1 == content2  # content same, but was re-fetched


def test_invalidate(mappings_dir):
    repo = MappingRepository(cache_ttl_seconds=3600)
    url = (mappings_dir / "customers.rml.ttl").as_uri()
    repo.fetch(url)
    assert url in repo._cache
    repo.invalidate(url)
    assert url not in repo._cache


def test_clear(mappings_dir):
    repo = MappingRepository(cache_ttl_seconds=3600)
    url = (mappings_dir / "customers.rml.ttl").as_uri()
    repo.fetch(url)
    repo.clear()
    assert len(repo._cache) == 0


def test_empty_url_raises():
    repo = MappingRepository()
    with pytest.raises(ValueError, match="empty"):
        repo.fetch("")


def test_unsupported_scheme():
    repo = MappingRepository()
    with pytest.raises(ValueError, match="Unsupported"):
        repo.fetch("ftp://example.com/mapping.ttl")


def test_http_fetch(httpserver, mappings_dir):
    content = (mappings_dir / "customers.rml.ttl").read_text()
    httpserver.expect_request("/mapping.ttl").respond_with_data(content, content_type="text/turtle")

    repo = MappingRepository(cache_ttl_seconds=60)
    result = repo.fetch(httpserver.url_for("/mapping.ttl"))
    assert result == content


def test_http_404_raises(httpserver):
    httpserver.expect_request("/missing.ttl").respond_with_data("Not Found", status=404)
    repo = MappingRepository()
    with pytest.raises(Exception):
        repo.fetch(httpserver.url_for("/missing.ttl"))
