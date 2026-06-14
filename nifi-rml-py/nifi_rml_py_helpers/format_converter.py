import io

import rdflib

from .constants import OutputFormat


def serialize(graph: rdflib.Graph, output_format: OutputFormat) -> bytes:
    """Serialize an rdflib Graph to bytes in the requested format."""
    buf = io.BytesIO()
    graph.serialize(destination=buf, format=output_format.rdflib_format)
    return buf.getvalue()
