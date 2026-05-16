package com.agreev.nifi.rml.engine.rml;

import com.agreev.nifi.rml.engine.RMLEngineException;
import com.agreev.nifi.rml.engine.rml.RMLMapping.ObjectMap;
import com.agreev.nifi.rml.engine.rml.RMLMapping.PredicateObjectMap;
import com.agreev.nifi.rml.engine.rml.RMLMapping.ReferenceFormulation;
import com.agreev.nifi.rml.engine.rml.RMLMapping.SubjectMap;
import com.agreev.nifi.rml.engine.rml.RMLMapping.TriplesMap;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.NodeIterator;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.vocabulary.RDF;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class RMLMappingParser {

    public static final String RR_NS  = "http://www.w3.org/ns/r2rml#";
    public static final String RML_NS = "http://semweb.mmlab.be/ns/rml#";
    public static final String QL_NS  = "http://semweb.mmlab.be/ns/ql#";

    public RMLMapping parse(Path turtleFile) throws RMLEngineException {
        System.err.printf("RMLMappingParser: Parsing mapping from file: %s%n", turtleFile.toAbsolutePath());
        if (!Files.exists(turtleFile)) {
            throw new RMLEngineException("Mapping file does not exist: " + turtleFile.toAbsolutePath());
        }
        if (!Files.isReadable(turtleFile)) {
            throw new RMLEngineException("Mapping file is not readable: " + turtleFile.toAbsolutePath());
        }
        try (InputStream in = Files.newInputStream(turtleFile)) {
            return parse(in);
        } catch (Exception e) {
            throw new RMLEngineException("Failed to parse RML mapping from " + turtleFile, e);
        }
    }

    public RMLMapping parse(InputStream turtleStream) throws RMLEngineException {
        System.err.println("RMLMappingParser: Parsing mapping from stream");
        Model model = ModelFactory.createDefaultModel();
        try {
            RDFParser.source(turtleStream).lang(Lang.TURTLE).parse(model);
            System.err.printf("RMLMappingParser: Parsed Turtle model with %d statements%n", model.size());
        } catch (Exception e) {
            throw new RMLEngineException("Failed to parse mapping document as Turtle", e);
        }
        return parseModel(model);
    }

    public RMLMapping parseModel(Model model) throws RMLEngineException {
        Property logicalSource = model.createProperty(RML_NS + "logicalSource");
        ResIterator triplesMapResources = model.listSubjectsWithProperty(logicalSource);
        List<TriplesMap> triplesMaps = new ArrayList<>();
        int count = 0;
        while (triplesMapResources.hasNext()) {
            Resource tm = triplesMapResources.nextResource();
            count++;
            System.err.printf("RMLMappingParser: Parsing TriplesMap #%d: %s%n", count, tm.getURI());
            triplesMaps.add(parseTriplesMap(model, tm));
        }
        if (triplesMaps.isEmpty()) {
            throw new RMLEngineException("No TriplesMap with rml:logicalSource found in mapping");
        }
        System.err.printf("RMLMappingParser: Successfully parsed %d triple maps%n", triplesMaps.size());
        return new RMLMapping(triplesMaps);
    }

    private TriplesMap parseTriplesMap(Model model, Resource tm) throws RMLEngineException {
        Resource logicalSource = mustGetResource(tm, model.createProperty(RML_NS + "logicalSource"),
            "rml:logicalSource");
        String sourcePath = mustGetString(logicalSource, model.createProperty(RML_NS + "source"),
            "rml:source");
        ReferenceFormulation refForm = parseReferenceFormulation(
            getResource(logicalSource, model.createProperty(RML_NS + "referenceFormulation")));
        String iterator = getString(logicalSource, model.createProperty(RML_NS + "iterator"));

        System.err.printf("RMLMappingParser:   source=%s%n", sourcePath);
        System.err.printf("RMLMappingParser:   referenceFormulation=%s%n", refForm);
        System.err.printf("RMLMappingParser:   iterator=%s%n", iterator);

        Resource subjectMapResource = mustGetResource(tm, model.createProperty(RR_NS + "subjectMap"),
            "rr:subjectMap");
        String template = mustGetString(subjectMapResource, model.createProperty(RR_NS + "template"),
            "rr:template");
        List<String> classIris = new ArrayList<>();
        NodeIterator classes = model.listObjectsOfProperty(subjectMapResource,
            model.createProperty(RR_NS + "class"));
        while (classes.hasNext()) {
            classIris.add(classes.next().asResource().getURI());
        }
        SubjectMap subjectMap = new SubjectMap(template, classIris);
        System.err.printf("RMLMappingParser:   subjectMap.template=%s%n", template);
        System.err.printf("RMLMappingParser:   subjectMap.classes=%s%n", classIris);

        List<PredicateObjectMap> predicateObjectMaps = new ArrayList<>();
        StmtIterator poStmts = model.listStatements(tm, model.createProperty(RR_NS + "predicateObjectMap"),
            (RDFNode) null);
        int poCount = 0;
        while (poStmts.hasNext()) {
            Statement po = poStmts.nextStatement();
            Resource poResource = po.getObject().asResource();
            poCount++;
            System.err.printf("RMLMappingParser:   PredicateObjectMap #%d%n", poCount);
            predicateObjectMaps.add(parsePredicateObjectMap(model, poResource));
        }

        System.err.printf("RMLMappingParser:   Total predicateObjectMaps=%d%n", predicateObjectMaps.size());
        return new TriplesMap(sourcePath, refForm, iterator, subjectMap, predicateObjectMaps);
    }

    private PredicateObjectMap parsePredicateObjectMap(Model model, Resource po) throws RMLEngineException {
        Resource predicateNode = getResource(po, model.createProperty(RR_NS + "predicate"));
        String predicateIri;
        if (predicateNode != null) {
            predicateIri = predicateNode.getURI();
        } else {
            Resource predicateMap = getResource(po, model.createProperty(RR_NS + "predicateMap"));
            if (predicateMap == null) {
                throw new RMLEngineException("predicateObjectMap missing rr:predicate or rr:predicateMap");
            }
            Resource constant = getResource(predicateMap, model.createProperty(RR_NS + "constant"));
            predicateIri = constant != null ? constant.getURI() : null;
            if (predicateIri == null) {
                throw new RMLEngineException("rr:predicateMap without rr:constant is not supported in subset");
            }
        }

        Resource objectMapResource = getResource(po, model.createProperty(RR_NS + "objectMap"));
        if (objectMapResource == null) {
            Resource constantObject = getResource(po, model.createProperty(RR_NS + "object"));
            if (constantObject != null) {
                return new PredicateObjectMap(predicateIri,
                    new ObjectMap(null, null, constantObject.getURI(), null, null));
            }
            String constantLiteral = getString(po, model.createProperty(RR_NS + "object"));
            if (constantLiteral != null) {
                return new PredicateObjectMap(predicateIri,
                    new ObjectMap(null, null, constantLiteral, null, null));
            }
            throw new RMLEngineException("predicateObjectMap missing rr:objectMap or rr:object");
        }

        String reference = getString(objectMapResource, model.createProperty(RML_NS + "reference"));
        String objectTemplate = getString(objectMapResource, model.createProperty(RR_NS + "template"));
        String constant = getString(objectMapResource, model.createProperty(RR_NS + "constant"));
        Resource datatype = getResource(objectMapResource, model.createProperty(RR_NS + "datatype"));
        String language = getString(objectMapResource, model.createProperty(RR_NS + "language"));

        ObjectMap objectMap = new ObjectMap(
            reference,
            objectTemplate,
            constant,
            datatype != null ? datatype.getURI() : null,
            language
        );
        return new PredicateObjectMap(predicateIri, objectMap);
    }

    private static ReferenceFormulation parseReferenceFormulation(Resource refForm) {
        if (refForm == null) {
            return ReferenceFormulation.JSONPATH;
        }
        String uri = refForm.getURI();
        if ((QL_NS + "JSONPath").equals(uri)) return ReferenceFormulation.JSONPATH;
        if ((QL_NS + "XPath").equals(uri))    return ReferenceFormulation.XPATH;
        if ((QL_NS + "CSV").equals(uri))      return ReferenceFormulation.CSV;
        throw new IllegalArgumentException("Unsupported reference formulation: " + uri);
    }

    private static Resource getResource(Resource subject, Property p) {
        Statement s = subject.getProperty(p);
        return s == null ? null : s.getObject().asResource();
    }

    private static Resource mustGetResource(Resource subject, Property p, String label)
            throws RMLEngineException {
        Resource r = getResource(subject, p);
        if (r == null) {
            throw new RMLEngineException("Missing required resource: " + label);
        }
        return r;
    }

    private static String getString(Resource subject, Property p) {
        Statement s = subject.getProperty(p);
        if (s == null) return null;
        RDFNode node = s.getObject();
        return node.isLiteral() ? node.asLiteral().getString() : node.toString();
    }

    private static String mustGetString(Resource subject, Property p, String label)
            throws RMLEngineException {
        String v = getString(subject, p);
        if (v == null || v.isEmpty()) {
            throw new RMLEngineException("Missing required literal: " + label);
        }
        return v;
    }

    @SuppressWarnings("unused")
    private static Property rdfType() {
        return RDF.type;
    }
}
