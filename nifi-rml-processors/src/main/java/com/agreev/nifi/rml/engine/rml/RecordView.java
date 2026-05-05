package com.agreev.nifi.rml.engine.rml;

public interface RecordView {

    /**
     * Returns the string value addressed by {@code reference} within the current record,
     * or {@code null} if the field is absent.
     */
    String get(String reference);
}
