from enum import Enum


class InputFormat(Enum):
    JSON = ("application/json", "json")
    CSV = ("text/csv", "csv")
    XML = ("application/xml", "xml")

    def __init__(self, mime_type: str, file_extension: str):
        self.mime_type = mime_type
        self.file_extension = file_extension

    @classmethod
    def from_string(cls, value: str) -> "InputFormat":
        return cls[value.strip().upper()]


class OutputFormat(Enum):
    TURTLE = ("text/turtle", "turtle", "ttl")
    NTRIPLES = ("application/n-triples", "nt", "nt")
    JSONLD = ("application/ld+json", "json-ld", "jsonld")
    RDFXML = ("application/rdf+xml", "xml", "rdf")

    def __init__(self, mime_type: str, rdflib_format: str, file_extension: str):
        self.mime_type = mime_type
        self.rdflib_format = rdflib_format
        self.file_extension = file_extension

    @classmethod
    def from_string(cls, value: str) -> "OutputFormat":
        normalized = value.strip().upper().replace("-", "").replace("/", "")
        return cls[normalized]
