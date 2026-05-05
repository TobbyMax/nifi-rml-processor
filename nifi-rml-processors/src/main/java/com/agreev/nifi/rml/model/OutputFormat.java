package com.agreev.nifi.rml.model;

public enum OutputFormat {

    TURTLE("text/turtle", "TURTLE"),

    NTRIPLES("application/n-triples", "N-TRIPLES"),

    JSONLD("application/ld+json", "JSON-LD"),

    RDFXML("application/rdf+xml", "RDF/XML");

    private final String mimeType;
    private final String jenaName;

    OutputFormat(String mimeType, String jenaName) {
        this.mimeType = mimeType;
        this.jenaName = jenaName;
    }

    public String mimeType() {
        return mimeType;
    }

    public String jenaName() {
        return jenaName;
    }

    public static OutputFormat fromString(String value) {
        String normalized = value.trim().toUpperCase().replace("-", "").replace("/", "");
        return OutputFormat.valueOf(normalized);
    }
}
