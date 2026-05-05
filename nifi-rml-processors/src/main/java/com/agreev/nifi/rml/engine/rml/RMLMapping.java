package com.agreev.nifi.rml.engine.rml;

import java.util.List;

public record RMLMapping(List<TriplesMap> triplesMaps) {

    public record TriplesMap(
        String logicalSourcePath,
        ReferenceFormulation referenceFormulation,
        String iterator,
        SubjectMap subjectMap,
        List<PredicateObjectMap> predicateObjectMaps
    ) { }

    public record SubjectMap(
        String template,
        List<String> classIris
    ) { }

    public record PredicateObjectMap(
        String predicateIri,
        ObjectMap objectMap
    ) { }

    public record ObjectMap(
        String reference,
        String template,
        String constant,
        String datatypeIri,
        String languageTag
    ) {
        public boolean isReference() { return reference != null; }
        public boolean isTemplate() { return template != null; }
        public boolean isConstant() { return constant != null; }
    }

    public enum ReferenceFormulation {
        JSONPATH,
        XPATH,
        CSV
    }
}
