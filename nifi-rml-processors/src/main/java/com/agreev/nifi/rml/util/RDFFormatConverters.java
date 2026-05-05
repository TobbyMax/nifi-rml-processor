package com.agreev.nifi.rml.util;

import com.agreev.nifi.rml.model.OutputFormat;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;

import java.io.OutputStream;

public final class RDFFormatConverters {

    private RDFFormatConverters() { }

    public static Lang toJenaLang(OutputFormat format) {
        switch (format) {
            case TURTLE:   return Lang.TURTLE;
            case NTRIPLES: return Lang.NTRIPLES;
            case JSONLD:   return Lang.JSONLD;
            case RDFXML:   return Lang.RDFXML;
            default:
                throw new IllegalArgumentException("Unsupported output format: " + format);
        }
    }

    public static void write(Model model, OutputStream out, OutputFormat format) {
        RDFDataMgr.write(out, model, toJenaLang(format));
    }
}
