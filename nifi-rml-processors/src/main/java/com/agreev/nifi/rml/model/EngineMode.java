package com.agreev.nifi.rml.model;

public enum EngineMode {

    AUTO,

    RMLMAPPER,

    MORPH_KGC;

    public static EngineMode fromString(String value) {
        if (value == null) {
            return AUTO;
        }
        return EngineMode.valueOf(value.trim().toUpperCase());
    }
}
