package com.agreev.nifi.rml.engine.rml;

import com.agreev.nifi.rml.engine.rml.RMLMapping.ObjectMap;
import com.agreev.nifi.rml.engine.rml.RMLMapping.PredicateObjectMap;
import com.agreev.nifi.rml.engine.rml.RMLMapping.SubjectMap;
import com.agreev.nifi.rml.engine.rml.RMLMapping.TriplesMap;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RecordMaterializer {

    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{([^}]+)\\}");

    private final Model model;

    public RecordMaterializer(Model model) {
        this.model = model;
    }

    public void materialize(TriplesMap tm, RecordView record) {
        SubjectMap sm = tm.subjectMap();
        String subjectIri = expandTemplate(sm.template(), record);
        if (subjectIri == null) {
            return;
        }
        Resource subject = model.createResource(subjectIri);

        for (String classIri : sm.classIris()) {
            subject.addProperty(RDF.type, model.createResource(classIri));
        }

        for (PredicateObjectMap po : tm.predicateObjectMaps()) {
            ObjectMap om = po.objectMap();
            String objectValue;
            boolean asResource;

            if (om.isReference()) {
                objectValue = record.get(om.reference());
                asResource = false;
            } else if (om.isTemplate()) {
                objectValue = expandTemplate(om.template(), record);
                asResource = true;
            } else if (om.isConstant()) {
                objectValue = om.constant();
                asResource = looksLikeIri(objectValue);
            } else {
                continue;
            }

            if (objectValue == null) {
                continue;
            }

            Resource predicate = model.createResource(po.predicateIri());
            if (asResource) {
                subject.addProperty(model.createProperty(po.predicateIri()),
                    model.createResource(objectValue));
            } else if (om.datatypeIri() != null) {
                subject.addLiteral(model.createProperty(po.predicateIri()),
                    model.createTypedLiteral(objectValue,
                        org.apache.jena.datatypes.TypeMapper.getInstance().getSafeTypeByName(om.datatypeIri())));
            } else if (om.languageTag() != null) {
                subject.addProperty(model.createProperty(po.predicateIri()),
                    objectValue, om.languageTag());
            } else {
                subject.addProperty(model.createProperty(po.predicateIri()), objectValue);
            }
        }
    }

    public static String expandTemplate(String template, RecordView record) {
        Matcher m = TEMPLATE_VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key = m.group(1);
            String value = record.get(key);
            if (value == null) {
                return null;
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean looksLikeIri(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://")
            || value.startsWith("urn:"));
    }
}
