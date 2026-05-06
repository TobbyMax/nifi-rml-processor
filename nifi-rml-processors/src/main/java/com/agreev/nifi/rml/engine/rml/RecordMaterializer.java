package com.agreev.nifi.rml.engine.rml;

import com.agreev.nifi.rml.engine.rml.RMLMapping.ObjectMap;
import com.agreev.nifi.rml.engine.rml.RMLMapping.PredicateObjectMap;
import com.agreev.nifi.rml.engine.rml.RMLMapping.SubjectMap;
import com.agreev.nifi.rml.engine.rml.RMLMapping.TriplesMap;
import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.vocabulary.RDF;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RecordMaterializer {

    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{([^}]+)\\}");

    private final StreamRDF stream;
    private long tripleCount;

    public RecordMaterializer(StreamRDF stream) {
        this.stream = stream;
    }

    public long tripleCount() {
        return tripleCount;
    }

    public void materialize(TriplesMap tm, RecordView record) {
        SubjectMap sm = tm.subjectMap();
        String subjectIri = expandTemplate(sm.template(), record);
        if (subjectIri == null) {
            return;
        }
        Node subject = NodeFactory.createURI(subjectIri);

        for (String classIri : sm.classIris()) {
            emit(subject, RDF.type.asNode(), NodeFactory.createURI(classIri));
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

            Node predicate = NodeFactory.createURI(po.predicateIri());
            Node object = buildObjectNode(objectValue, asResource, om);
            emit(subject, predicate, object);
        }
    }

    private Node buildObjectNode(String value, boolean asResource, ObjectMap om) {
        if (asResource) {
            return NodeFactory.createURI(value);
        }
        if (om.datatypeIri() != null) {
            RDFDatatype dt = TypeMapper.getInstance().getSafeTypeByName(om.datatypeIri());
            return NodeFactory.createLiteralByValue(value, dt);
        }
        if (om.languageTag() != null) {
            return NodeFactory.createLiteralLang(value, om.languageTag());
        }
        return NodeFactory.createLiteralString(value);
    }

    private void emit(Node subject, Node predicate, Node object) {
        stream.triple(Triple.create(subject, predicate, object));
        tripleCount++;
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
