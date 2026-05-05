package com.agreev.nifi.rml;

import com.agreev.nifi.rml.yarrrml.YARRRMLParser;
import org.apache.nifi.annotation.behavior.InputRequirement;
import org.apache.nifi.annotation.behavior.SupportsBatching;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.ProcessContext;

import java.io.IOException;

@Tags({"rml", "yarrrml", "yaml", "rdf", "mapping", "semantic", "knowledge graph"})
@CapabilityDescription("Executes a YARRRML mapping by transpiling it to RML and delegating to the "
    + "shared RML execution pipeline (engine selection, in-process or Morph-KGC).")
@InputRequirement(InputRequirement.Requirement.INPUT_REQUIRED)
@SupportsBatching
public class ExecuteYARRRMLMappingProcessor extends ExecuteRMLMappingProcessor {

    private final YARRRMLParser yarrrmlParser = new YARRRMLParser();

    @Override
    protected String readMapping(ProcessContext context, FlowFile flowFile) throws IOException {
        String yarrrmlSource = super.readMapping(context, flowFile);
        return yarrrmlParser.parse(yarrrmlSource);
    }
}
