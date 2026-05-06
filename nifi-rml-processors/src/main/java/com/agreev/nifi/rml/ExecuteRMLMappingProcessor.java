package com.agreev.nifi.rml;

import com.agreev.nifi.rml.engine.EngineSelection;
import com.agreev.nifi.rml.engine.EngineSelectionStrategy;
import com.agreev.nifi.rml.engine.MorphKGCEngine;
import com.agreev.nifi.rml.engine.RMLEngine;
import com.agreev.nifi.rml.engine.RMLEngineException;
import com.agreev.nifi.rml.engine.RMLEngineRegistry;
import com.agreev.nifi.rml.engine.RMLMapperEngine;
import com.agreev.nifi.rml.model.EngineMode;
import com.agreev.nifi.rml.model.InputFormat;
import com.agreev.nifi.rml.model.MappingRequest;
import com.agreev.nifi.rml.model.MappingResult;
import com.agreev.nifi.rml.model.OutputFormat;
import com.agreev.nifi.rml.repository.MappingRepository;
import com.agreev.nifi.rml.util.TempFiles;
import org.apache.nifi.flowfile.FlowFile;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    static final AllowableValue MAPPING_SOURCE_URL = new AllowableValue(
        "URL", "URL / repository",
        "RML mapping is fetched from an HTTP(S) URL, file://, or classpath: URI. "
            + "Supports raw GitHub/GitLab files, S3 presigned URLs, internal Git mirrors via HTTP.");

    static final PropertyDescriptor MAPPING_SOURCE = new PropertyDescriptor.Builder()
        .name("mapping-source")
        .displayName("Mapping source")
        .description("Where to read the RML mapping document from")
        .required(true)
        .allowableValues(MAPPING_SOURCE_INLINE, MAPPING_SOURCE_FILE,
            MAPPING_SOURCE_ATTRIBUTE, MAPPING_SOURCE_URL)
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

    static final PropertyDescriptor MAPPING_URL = new PropertyDescriptor.Builder()
        .name("mapping-url")
        .displayName("Mapping URL")
        .description("URL of the mapping document. Supports http(s)://, file://, classpath: schemes. "
            + "Expression Language is evaluated, so per-tenant or per-flow templating is possible "
            + "(e.g. http://mappings.internal/${tenant.id}/customer.ttl).")
        .required(false)
        .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
        .expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
        .build();

    static final PropertyDescriptor MAPPING_CACHE_TTL_SECONDS = new PropertyDescriptor.Builder()
        .name("mapping-cache-ttl-seconds")
        .displayName("Mapping URL cache TTL (seconds)")
        .description("How long to cache mappings fetched from URL before refetching. "
            + "Set to 0 to disable caching.")
        .required(true)
        .addValidator(StandardValidators.LONG_VALIDATOR)
        .defaultValue("600")
        .build();

    static final PropertyDescriptor INPUT_DATA_FORMAT = new PropertyDescriptor.Builder()
        .name("input-data-format")
        .displayName("Input data format")
        .description("Format of the FlowFile content")
        .required(true)
        .allowableValues("JSON", "CSV", "XML")
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
        MAPPING_URL, MAPPING_CACHE_TTL_SECONDS,
        INPUT_DATA_FORMAT, OUTPUT_RDF_FORMAT,
        ENGINE_MODE, AUTO_THRESHOLD_BYTES, MORPH_KGC_COMMAND, MORPH_KGC_FALLBACK,
        BASE_IRI, TEMPORARY_DIRECTORY
    );

    private static final Set<Relationship> RELATIONSHIPS = new HashSet<>(
        Set.of(REL_SUCCESS, REL_FAILURE, REL_ORIGINAL));

    protected RMLEngineRegistry registry;
    protected EngineSelectionStrategy selectionStrategy;
    protected MappingRepository mappingRepository;

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

        Path tempDir = Paths.get(context.getProperty(TEMPORARY_DIRECTORY).getValue());
        long ttlSeconds = context.getProperty(MAPPING_CACHE_TTL_SECONDS).asLong();
        mappingRepository = new MappingRepository(
            tempDir.resolve("mapping-cache"),
            Duration.ofSeconds(ttlSeconds));
    }

    @OnStopped
    public void onStopped() {
        if (registry != null) {
            registry.close();
            registry = null;
        }
        selectionStrategy = null;
        if (mappingRepository != null) {
            mappingRepository.clearCache();
            mappingRepository = null;
        }
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        FlowFile original = session.get();
        if (original == null) {
            return;
        }

        Path workDir = Paths.get(context.getProperty(TEMPORARY_DIRECTORY).getValue());
        TempFiles.ensureDir(workDir);

        Path inputCopy = TempFiles.createTempFile(workDir, "rml-in-", ".bin");
        Path mappingCopy = TempFiles.createTempFile(workDir, "rml-map-", ".ttl");

        try {
            try (InputStream in = session.read(original)) {
                Files.copy(in, inputCopy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            String mappingContent = readMapping(context, original);
            Files.writeString(mappingCopy, mappingContent, StandardCharsets.UTF_8);

            EngineMode mode = EngineMode.fromString(context.getProperty(ENGINE_MODE).getValue());
            long threshold = context.getProperty(AUTO_THRESHOLD_BYTES).asLong();
            long inputSize = original.getSize();
            EngineSelection selection = selectionStrategy.select(mode, inputSize, threshold);

            MappingRequest request = MappingRequest.builder()
                .inputData(inputCopy)
                .inputSizeBytes(inputSize)
                .inputFormat(InputFormat.fromString(context.getProperty(INPUT_DATA_FORMAT).getValue()))
                .mappingDocument(mappingCopy)
                .outputFormat(OutputFormat.fromString(context.getProperty(OUTPUT_RDF_FORMAT).getValue()))
                .baseIri(context.getProperty(BASE_IRI).getValue())
                .workingDirectory(workDir)
                .build();

            RMLEngine engine = selection.engine();
            MappingResult result = engine.execute(request);

            FlowFile output = session.create(original);
            output = session.importFrom(result.output(), output);

            Map<String, String> attrs = new HashMap<>();
            attrs.put("rml.engine.selected", result.engineId());
            attrs.put("rml.engine.reason", selection.reason().name());
            attrs.put("rml.input.size.bytes", Long.toString(inputSize));
            attrs.put("rml.output.format", result.outputFormat().name());
            attrs.put("rml.triples.count", Long.toString(result.triplesCount()));
            attrs.put("rml.duration.ms", Long.toString(result.durationMillis()));
            attrs.put("mime.type", result.outputFormat().mimeType());
            output = session.putAllAttributes(output, attrs);

            session.transfer(output, REL_SUCCESS);
            session.transfer(original, REL_ORIGINAL);

            TempFiles.deleteSilently(result.output());

        } catch (RMLEngineException e) {
            getLogger().error("RML engine execution failed: {}", new Object[]{e.getMessage()}, e);
            FlowFile failed = session.putAttribute(original, "rml.error.message", e.getMessage());
            failed = session.putAttribute(failed, "rml.error.type", e.getClass().getSimpleName());
            session.transfer(failed, REL_FAILURE);
        } catch (IOException e) {
            getLogger().error("I/O failure in RML processor: {}", new Object[]{e.getMessage()}, e);
            FlowFile failed = session.putAttribute(original, "rml.error.message", e.getMessage());
            failed = session.putAttribute(failed, "rml.error.type", e.getClass().getSimpleName());
            session.transfer(failed, REL_FAILURE);
        } catch (RuntimeException e) {
            getLogger().error("Unexpected failure in RML processor: {}", new Object[]{e.getMessage()}, e);
            FlowFile failed = session.putAttribute(original, "rml.error.message", e.getMessage());
            failed = session.putAttribute(failed, "rml.error.type", e.getClass().getSimpleName());
            session.transfer(failed, REL_FAILURE);
        } finally {
            TempFiles.deleteSilently(inputCopy);
            TempFiles.deleteSilently(mappingCopy);
        }
    }

    protected String readMapping(ProcessContext context, FlowFile flowFile) throws IOException {
        String source = context.getProperty(MAPPING_SOURCE).getValue();
        if (MAPPING_SOURCE_INLINE.getValue().equals(source)) {
            return context.getProperty(MAPPING_CONTENT)
                .evaluateAttributeExpressions(flowFile)
                .getValue();
        }
        if (MAPPING_SOURCE_FILE.getValue().equals(source)) {
            String pathValue = context.getProperty(MAPPING_FILE)
                .evaluateAttributeExpressions(flowFile)
                .getValue();
            return Files.readString(Paths.get(pathValue), StandardCharsets.UTF_8);
        }
        if (MAPPING_SOURCE_ATTRIBUTE.getValue().equals(source)) {
            String attrName = context.getProperty(MAPPING_ATTRIBUTE).getValue();
            String value = flowFile.getAttribute(attrName);
            if (value == null || value.isBlank()) {
                throw new IOException("Mapping attribute '" + attrName + "' is missing or empty");
            }
            return value;
        }
        if (MAPPING_SOURCE_URL.getValue().equals(source)) {
            String url = context.getProperty(MAPPING_URL)
                .evaluateAttributeExpressions(flowFile)
                .getValue();
            if (url == null || url.isBlank()) {
                throw new IOException("Mapping URL is empty");
            }
            return mappingRepository.fetch(url);
        }
        throw new IOException("Unknown mapping source: " + source);
    }
}
