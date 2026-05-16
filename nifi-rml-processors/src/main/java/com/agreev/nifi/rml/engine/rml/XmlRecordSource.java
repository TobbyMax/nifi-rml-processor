package com.agreev.nifi.rml.engine.rml;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;

public final class XmlRecordSource implements Iterable<RecordView> {

    private final Document document;
    private final NodeList records;
    private final XPath xPath;
    private final Path inputPath;
    private final String iteratorExpression;

    public XmlRecordSource(Path inputPath, String iterator) throws IOException {
        this.inputPath = inputPath;
        this.iteratorExpression = iterator == null || iterator.isEmpty() ? "/*" : iterator;

        if (!Files.exists(inputPath)) {
            throw new IOException("Input XML file does not exist: " + inputPath.toAbsolutePath());
        }
        if (!Files.isReadable(inputPath)) {
            throw new IOException("Input XML file is not readable: " + inputPath.toAbsolutePath());
        }

        try (InputStream in = Files.newInputStream(inputPath)) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            disableXXEVulnerability(dbf);

            DocumentBuilder db = dbf.newDocumentBuilder();
            this.document = db.parse(new InputSource(in));

            XPathFactory xPathFactory = XPathFactory.newInstance();
            this.xPath = xPathFactory.newXPath();

            this.records = (NodeList) this.xPath.evaluate(
                this.iteratorExpression,
                document,
                XPathConstants.NODESET);

            if (records.getLength() == 0) {
                System.err.printf("Warning: XPath iterator '%s' matched 0 records in %s%n",
                    this.iteratorExpression, inputPath.getFileName());
            }
        } catch (ParserConfigurationException | XPathExpressionException
                | org.xml.sax.SAXException e) {
            throw new IOException(
                String.format("Failed to parse XML input %s with iterator '%s': %s",
                    inputPath.toAbsolutePath(), this.iteratorExpression, e.getMessage()),
                e);
        }
    }

    private void disableXXEVulnerability(DocumentBuilderFactory dbf) throws ParserConfigurationException {
        try {
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
        } catch (ParserConfigurationException e) {
            System.err.println("Warning: Could not fully disable XXE protection: " + e.getMessage());
        }
    }

    public int size() {
        return records.getLength();
    }

    @Override
    public Iterator<RecordView> iterator() {
        return new Iterator<>() {
            private int idx = 0;

            @Override public boolean hasNext() { return idx < records.getLength(); }

            @Override
            public RecordView next() {
                Node node = records.item(idx++);
                return reference -> evaluate(node, reference);
            }
        };
    }

    private String evaluate(Node node, String reference) {
        if (node == null) {
            System.err.println("Warning: Node is null in XML record source");
            return null;
        }
        if (reference == null || reference.isEmpty()) {
            System.err.println("Warning: Empty or null reference in XML evaluation");
            return null;
        }

        try {
            String expression = normalizeXPathExpression(reference);
            Object result = xPath.evaluate(expression, node, XPathConstants.STRING);
            String value = result == null ? null : result.toString();
            return (value != null && !value.isEmpty()) ? value : null;
        } catch (XPathExpressionException e) {
            System.err.printf("XPath evaluation failed for expression '%s' on node '%s': %s%n",
                reference, getNodeDescription(node), e.getMessage());
            return null;
        }
    }

    private String normalizeXPathExpression(String reference) {
        if (reference.startsWith("@") || reference.startsWith(".") || reference.startsWith("/")) {
            return reference;
        }
        return reference;
    }

    private String getNodeDescription(Node node) {
        if (node == null) return "null";
        String name = node.getNodeName();
        String value = node.getNodeValue();
        if (value != null && value.length() > 20) {
            value = value.substring(0, 20) + "...";
        }
        return String.format("%s=%s", name, value);
    }
}
