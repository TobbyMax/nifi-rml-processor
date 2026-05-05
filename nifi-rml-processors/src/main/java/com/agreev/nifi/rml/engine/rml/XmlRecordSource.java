package com.agreev.nifi.rml.engine.rml;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

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
    private final XPathFactory xPathFactory;

    public XmlRecordSource(Path inputPath, String iterator) throws IOException {
        try (InputStream in = Files.newInputStream(inputPath)) {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            this.document = db.parse(new InputSource(in));
            this.xPathFactory = XPathFactory.newInstance();
            XPath xp = xPathFactory.newXPath();
            this.records = (NodeList) xp.evaluate(
                iterator == null || iterator.isEmpty() ? "/*" : iterator,
                document,
                XPathConstants.NODESET);
        } catch (ParserConfigurationException | XPathExpressionException
                | org.xml.sax.SAXException e) {
            throw new IOException("Failed to parse XML input " + inputPath, e);
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
        try {
            XPath xp = xPathFactory.newXPath();
            String expression = reference.startsWith("@") || reference.startsWith(".") || reference.startsWith("/")
                ? reference
                : reference; // e.g. "name" interpreted as element name relative to node
            Object result = xp.evaluate(expression, node, XPathConstants.STRING);
            String value = result == null ? null : result.toString();
            return value == null || value.isEmpty() ? null : value;
        } catch (XPathExpressionException e) {
            return null;
        }
    }
}
