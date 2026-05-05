package com.agreev.nifi.rml.model;

public enum InputFormat {

    JSON("application/json"),

    CSV("text/csv"),

    XML("application/xml");

    private final String mimeType;

    InputFormat(String mimeType) {
        this.mimeType = mimeType;
    }

    public String mimeType() {
        return mimeType;
    }

    public static InputFormat fromString(String value) {
        return InputFormat.valueOf(value.trim().toUpperCase());
    }
}
