package com.agreev.nifi.rml.model;

public enum OutputFormat {

    TURTLE("text/turtle", "TURTLE", "ttl"),

    NTRIPLES("application/n-triples", "N-TRIPLES", "nt"),

    JSONLD("application/ld+json", "JSON-LD", "jsonld"),

    RDFXML("application/rdf+xml", "RDF/XML", "rdf");

    private final String mimeType;
    private final String jenaName;
    private final String fileExtension;

    OutputFormat(String mimeType, String jenaName, String fileExtension) {
        this.mimeType = mimeType;
        this.jenaName = jenaName;
        this.fileExtension = fileExtension;
    }

    public String mimeType() {
        return mimeType;
    }

    public String jenaName() {
        return jenaName;
    }

    public String fileExtension() {
        return fileExtension;
    }

    public static OutputFormat fromString(String value) {
        String normalized = value.trim().toUpperCase().replace("-", "").replace("/", "");
        return OutputFormat.valueOf(normalized);
    }
}
