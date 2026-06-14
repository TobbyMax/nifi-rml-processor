import hashlib
import importlib.resources
import time
from pathlib import Path
from urllib.parse import urlparse

import requests


class MappingRepository:
    """Fetch and TTL-cache RML mapping documents by URL/URI."""

    def __init__(self, cache_ttl_seconds: int = 600):
        self._ttl = cache_ttl_seconds
        # url -> (body: str, fetched_at: float)
        self._cache: dict[str, tuple[str, float]] = {}
        self._session = requests.Session()
        self._session.headers["User-Agent"] = "nifi-rml-py/0.1"

    def fetch(self, url: str) -> str:
        if not url or not url.strip():
            raise ValueError("Mapping URL is empty")

        now = time.monotonic()
        entry = self._cache.get(url)
        if entry and (now - entry[1]) < self._ttl:
            return entry[0]

        body = self._fetch_uncached(url)
        self._cache[url] = (body, now)
        return body

    def _fetch_uncached(self, url: str) -> str:
        parsed = urlparse(url)
        scheme = parsed.scheme.lower()

        if scheme in ("http", "https"):
            resp = self._session.get(url, timeout=30)
            resp.raise_for_status()
            return resp.text

        if scheme == "file":
            return Path(parsed.path).read_text(encoding="utf-8")

        if scheme == "classpath":
            path = parsed.path.lstrip("/")
            pkg, _, name = path.rpartition("/")
            pkg = pkg.replace("/", ".")
            data = importlib.resources.read_text(pkg, name, encoding="utf-8")
            return data

        raise ValueError(f"Unsupported mapping URL scheme: {scheme}")

    def invalidate(self, url: str) -> None:
        self._cache.pop(url, None)

    def clear(self) -> None:
        self._cache.clear()

    def close(self) -> None:
        self._session.close()
