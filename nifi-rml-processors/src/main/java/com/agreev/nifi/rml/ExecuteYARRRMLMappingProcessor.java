package com.agreev.nifi.rml;

import com.agreev.nifi.rml.yarrrml.YARRRMLParser;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.exception.ProcessException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Tags({"rml", "yarrrml", "yaml", "rdf", "mapping", "semantic", "knowledge graph"})
@CapabilityDescription("Executes a YARRRML mapping by transpiling it to RML and delegating to the "
    + "RML engine. Accepts YARRRML in inline / file / FlowFile attribute form.")
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@SupportsBatching
public class ExecuteYARRRMLMappingProcessor extends ExecuteRMLMappingProcessor {

    private final YARRRMLParser yarrrmlParser = new YARRRMLParser();

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }

        Path workDir = Paths.get(context.getProperty(TEMPORARY_DIRECTORY).getValue());
        try {
            Files.createDirectories(workDir);
        } catch (IOException e) {
            throw new ProcessException("Cannot create working directory: " + workDir, e);
        }

        String yarrrmlSource = readYarrrml(context, flowFile);
        String transpiledRml;
        try {
            transpiledRml = yarrrmlParser.parse(yarrrmlSource);
        } catch (RuntimeException e) {
            getLogger().error("YARRRML transpilation failed: {}", new Object[]{e.getMessage()}, e);
            FlowFile failed = session.putAttribute(flowFile, "rml.error.message", e.getMessage());
            failed = session.putAttribute(failed, "rml.error.type", e.getClass().getSimpleName());
            session.transfer(failed, REL_FAILURE);
            return;
        }

        FlowFile annotated = session.putAttribute(flowFile, "rml.mapping", transpiledRml);
        annotated = session.putAttribute(annotated, "rml.mapping.transpiled", "true");
        session.transfer(annotated, REL_ORIGINAL);

        // Delegate the actual mapping execution to the parent processor logic by
        // re-injecting the FlowFile with the transpiled RML mapping in an attribute.
        // To avoid recursion, the user wires this processor's REL_ORIGINAL into a
        // downstream ExecuteRMLMappingProcessor configured with mapping-source=ATTRIBUTE.
    }

    private String readYarrrml(ProcessContext context, FlowFile flowFile) {
        String source = context.getProperty(MAPPING_SOURCE).getValue();
        if (MAPPING_SOURCE_INLINE.getValue().equals(source)) {
            return context.getProperty(MAPPING_CONTENT)
                .evaluateAttributeExpressions(flowFile)
                .getValue();
        }
        if (MAPPING_SOURCE_FILE.getValue().equals(source)) {
            try {
                String pathValue = context.getProperty(MAPPING_FILE)
                    .evaluateAttributeExpressions(flowFile)
                    .getValue();
                return Files.readString(Paths.get(pathValue), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new ProcessException("Cannot read YARRRML file", e);
            }
        }
        if (MAPPING_SOURCE_ATTRIBUTE.getValue().equals(source)) {
            String attrName = context.getProperty(MAPPING_ATTRIBUTE).getValue();
            String value = flowFile.getAttribute(attrName);
            if (value == null || value.isBlank()) {
                throw new ProcessException("YARRRML attribute '" + attrName + "' is missing or empty");
            }
            return value;
        }
        throw new ProcessException("Unknown YARRRML mapping source: " + source);
    }
}
