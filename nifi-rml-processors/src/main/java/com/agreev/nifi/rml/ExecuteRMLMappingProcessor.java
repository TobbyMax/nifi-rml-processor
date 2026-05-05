package com.agreev.nifi.rml;

import com.agreev.nifi.rml.engine.EngineSelectionStrategy;
import com.agreev.nifi.rml.engine.MorphKGCEngine;
import com.agreev.nifi.rml.engine.RMLEngineRegistry;
import com.agreev.nifi.rml.engine.RMLMapperEngine;
import com.agreev.nifi.rml.model.EngineMode;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.annotation.lifecycle.OnStopped;
import org.apache.nifi.components.AllowableValue;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Tags({"rml", "rdf", "mapping", "semantic", "knowledge graph", "linked data"})
@CapabilityDescription("Executes an RML mapping against incoming FlowFile content (JSON in this build) "
    + "and emits the resulting RDF graph. Supports an in-process RML engine and an external Morph-KGC "
    + "engine, with an automatic mode that picks an engine based on input size.")
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@SupportsBatching
@WritesAttributes({
    @WritesAttribute(attribute = "rml.engine.selected", description = "Engine that processed the FlowFile"),
    @WritesAttribute(attribute = "rml.engine.reason", description = "Why that engine was selected"),
    @WritesAttribute(attribute = "rml.input.size.bytes", description = "Input FlowFile size in bytes"),
    @WritesAttribute(attribute = "rml.output.format", description = "Generated RDF format"),
    @WritesAttribute(attribute = "rml.triples.count", description = "Number of statements emitted"),
    @WritesAttribute(attribute = "rml.duration.ms", description = "Engine execution time"),
    @WritesAttribute(attribute = "rml.error.message", description = "Error message on failure"),
    @WritesAttribute(attribute = "mime.type", description = "MIME type of the generated RDF")
})
public class ExecuteRMLMappingProcessor extends AbstractProcessor {

    static final AllowableValue MAPPING_SOURCE_INLINE = new AllowableValue(
        "INLINE", "Inline", "RML mapping is provided in the processor property");
    static final AllowableValue MAPPING_SOURCE_FILE = new AllowableValue(
        "FILE", "File", "RML mapping is read from the filesystem");
    static final AllowableValue MAPPING_SOURCE_ATTRIBUTE = new AllowableValue(
        "ATTRIBUTE", "FlowFile attribute", "RML mapping is read from a FlowFile attribute");

    static final PropertyDescriptor MAPPING_SOURCE = new PropertyDescriptor.Builder()
        .name("mapping-source")
        .displayName("Mapping source")
        .description("Where to read the RML mapping document from")
        .required(true)
        .allowableValues(MAPPING_SOURCE_INLINE, MAPPING_SOURCE_FILE, MAPPING_SOURCE_ATTRIBUTE)
        .defaultValue(MAPPING_SOURCE_INLINE.getValue())
        .build();

    static final PropertyDescriptor MAPPING_CONTENT = new PropertyDescriptor.Builder()
        .name("mapping-content")
        .displayName("Inline mapping (RML/Turtle)")
        .description("The RML mapping document in Turtle. Required when mapping source is INLINE.")
        .required(false)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
        .build();

    static final PropertyDescriptor MAPPING_FILE = new PropertyDescriptor.Builder()
        .name("mapping-file")
        .displayName("Mapping file path")
        .description("Path to a Turtle file with the RML mapping. Required when mapping source is FILE.")
        .required(false)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
        .build();

    static final PropertyDescriptor MAPPING_ATTRIBUTE = new PropertyDescriptor.Builder()
        .name("mapping-attribute")
        .displayName("Mapping FlowFile attribute")
        .description("Name of the FlowFile attribute that carries the RML mapping. "
            + "Required when mapping source is ATTRIBUTE.")
        .required(false)
        .defaultValue("rml.mapping")
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build();

    static final PropertyDescriptor INPUT_DATA_FORMAT = new PropertyDescriptor.Builder()
        .name("input-data-format")
        .displayName("Input data format")
        .description("Format of the FlowFile content (this build supports JSON only)")
        .required(true)
        .allowableValues("JSON")
        .defaultValue("JSON")
        .build();

    static final PropertyDescriptor OUTPUT_RDF_FORMAT = new PropertyDescriptor.Builder()
        .name("output-rdf-format")
        .displayName("Output RDF format")
        .required(true)
        .allowableValues("TURTLE", "NTRIPLES", "JSONLD", "RDFXML")
        .defaultValue("TURTLE")
        .build();

    static final PropertyDescriptor ENGINE_MODE = new PropertyDescriptor.Builder()
        .name("engine-mode")
        .displayName("Engine selection mode")
        .description("AUTO picks an engine by input size; RMLMAPPER forces in-process; "
            + "MORPH_KGC forces the external CLI engine")
        .required(true)
        .allowableValues(EngineMode.AUTO.name(), EngineMode.RMLMAPPER.name(), EngineMode.MORPH_KGC.name())
        .defaultValue(EngineMode.AUTO.name())
        .build();

    static final PropertyDescriptor AUTO_THRESHOLD_BYTES = new PropertyDescriptor.Builder()
        .name("auto-threshold-bytes")
        .displayName("AUTO threshold (bytes)")
        .description("FlowFiles up to this size are routed to the in-process engine")
        .required(true)
        .addValidator(StandardValidators.LONG_VALIDATOR)
        .defaultValue(String.valueOf(50L * 1024L * 1024L))
        .build();

    static final PropertyDescriptor MORPH_KGC_COMMAND = new PropertyDescriptor.Builder()
        .name("morph-kgc-command")
        .displayName("Morph-KGC command")
        .description("Executable name or absolute path of the Morph-KGC CLI")
        .required(true)
        .defaultValue("morph-kgc")
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build();

    static final PropertyDescriptor MORPH_KGC_FALLBACK = new PropertyDescriptor.Builder()
        .name("morph-kgc-fallback")
        .displayName("AUTO fallback when Morph-KGC is unavailable")
        .description("RMLMAPPER routes large files to the in-process engine if Morph-KGC is missing; "
            + "MORPH_KGC fails the FlowFile instead")
        .required(true)
        .allowableValues(EngineMode.RMLMAPPER.name(), EngineMode.MORPH_KGC.name())
        .defaultValue(EngineMode.RMLMAPPER.name())
        .build();

    static final PropertyDescriptor BASE_IRI = new PropertyDescriptor.Builder()
        .name("base-iri")
        .displayName("Base IRI")
        .description("Base IRI used for relative references in the mapping")
        .required(true)
        .defaultValue("http://example.org/")
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build();

    static final PropertyDescriptor TEMPORARY_DIRECTORY = new PropertyDescriptor.Builder()
        .name("temporary-directory")
        .displayName("Temporary directory")
        .description("Directory for temporary input/output files")
        .required(true)
        .defaultValue(System.getProperty("java.io.tmpdir") + "/nifi-rml")
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .build();

    static final Relationship REL_SUCCESS = new Relationship.Builder()
        .name("success")
        .description("FlowFiles whose RDF graph was generated successfully")
        .build();

    static final Relationship REL_FAILURE = new Relationship.Builder()
        .name("failure")
        .description("FlowFiles whose mapping execution failed")
        .build();

    static final Relationship REL_ORIGINAL = new Relationship.Builder()
        .name("original")
        .description("Copy of the original input FlowFile for audit")
        .build();

    private static final List<PropertyDescriptor> PROPERTIES = List.of(
        MAPPING_SOURCE, MAPPING_CONTENT, MAPPING_FILE, MAPPING_ATTRIBUTE,
        INPUT_DATA_FORMAT, OUTPUT_RDF_FORMAT,
        ENGINE_MODE, AUTO_THRESHOLD_BYTES, MORPH_KGC_COMMAND, MORPH_KGC_FALLBACK,
        BASE_IRI, TEMPORARY_DIRECTORY
    );

    private static final Set<Relationship> RELATIONSHIPS = new HashSet<>(
        Set.of(REL_SUCCESS, REL_FAILURE, REL_ORIGINAL));

    protected RMLEngineRegistry registry;
    protected EngineSelectionStrategy selectionStrategy;

    @Override
    public Set<Relationship> getRelationships() {
        return RELATIONSHIPS;
    }

    @Override
    public List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return PROPERTIES;
    }

    @OnScheduled
    public void onScheduled(ProcessContext context) {
        registry = new RMLEngineRegistry();
        registry.register(new RMLMapperEngine());
        registry.register(new MorphKGCEngine(
            context.getProperty(MORPH_KGC_COMMAND).getValue(),
            300_000L));
        EngineMode fallback = EngineMode.fromString(context.getProperty(MORPH_KGC_FALLBACK).getValue());
        selectionStrategy = new EngineSelectionStrategy(registry, fallback);
    }

    @OnStopped
    public void onStopped() {
        if (registry != null) {
            registry.close();
            registry = null;
        }
        selectionStrategy = null;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        throw new ProcessException("ExecuteRMLMappingProcessor onTrigger pending implementation");
    }
}
