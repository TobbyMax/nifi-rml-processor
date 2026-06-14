from pathlib import Path
import pytest

FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture
def fixtures_dir() -> Path:
    return FIXTURES


@pytest.fixture
def data_dir(fixtures_dir) -> Path:
    return fixtures_dir / "data"


@pytest.fixture
def mappings_dir(fixtures_dir) -> Path:
    return fixtures_dir / "mappings"


@pytest.fixture
def expected_dir(fixtures_dir) -> Path:
    return fixtures_dir / "expected"
