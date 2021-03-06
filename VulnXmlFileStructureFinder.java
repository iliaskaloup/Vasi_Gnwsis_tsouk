/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.filestructurefinder;

import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.xpack.core.ml.filestructurefinder.FieldStats;
import org.elasticsearch.xpack.core.ml.filestructurefinder.FileStructure;
import org.elasticsearch.xpack.ml.filestructurefinder.TimestampFormatFinder.TimestampMatch;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

public class XmlFileStructureFinder implements FileStructureFinder {

    private final List<String> sampleMessages;
    private final FileStructure structure;

    static XmlFileStructureFinder makeXmlFileStructureFinder(List<String> explanation, String sample, String charsetName,
                                                             Boolean hasByteOrderMarker, FileStructureOverrides overrides)
        throws IOException, ParserConfigurationException, SAXException {

        String messagePrefix;
        try (Scanner scanner = new Scanner(sample)) {
            messagePrefix = scanner.next();
        }

        DocumentBuilderFactory docBuilderFactory = DocumentBuilderFactory.newInstance();
        docBuilderFactory.setNamespaceAware(false);
        docBuilderFactory.setValidating(false);

        List<String> sampleMessages = new ArrayList<>();
        List<Map<String, ?>> sampleRecords = new ArrayList<>();

        String[] sampleDocEnds = sample.split(Pattern.quote(messagePrefix));
        StringBuilder preamble = new StringBuilder(sampleDocEnds[0]);
        int linesConsumed = numNewlinesIn(sampleDocEnds[0]);
        for (int i = 1; i < sampleDocEnds.length; ++i) {
            String sampleDoc = messagePrefix + sampleDocEnds[i];
            if (i < 3) {
                preamble.append(sampleDoc);
            }
            DocumentBuilder docBuilder = docBuilderFactory.newDocumentBuilder();
            try (InputStream is = new ByteArrayInputStream(sampleDoc.getBytes(StandardCharsets.UTF_8))) {
                sampleRecords.add(docToMap(docBuilder.parse(is)));
                sampleMessages.add(sampleDoc);
                linesConsumed += numNewlinesIn(sampleDoc);
            } catch (SAXException e) {
                // Tolerate an incomplete last record as long as we have one complete record
                if (sampleRecords.isEmpty() || i < sampleDocEnds.length - 1) {
                    throw e;
                }
            }
        }

        if (sample.endsWith("\n") == false) {
            ++linesConsumed;
        }

        // If we get here the XML parser should have confirmed this
        assert messagePrefix.charAt(0) == '<';
        String topLevelTag = messagePrefix.substring(1);

        FileStructure.Builder structureBuilder = new FileStructure.Builder(FileStructure.Format.XML)
            .setCharset(charsetName)
            .setHasByteOrderMarker(hasByteOrderMarker)
            .setSampleStart(preamble.toString())
            .setNumLinesAnalyzed(linesConsumed)
            .setNumMessagesAnalyzed(sampleRecords.size())
            .setMultilineStartPattern("^\\s*<" + topLevelTag);

        Tuple<String, TimestampMatch> timeField = FileStructureUtils.guessTimestampField(explanation, sampleRecords, overrides);
        if (timeField != null) {
            structureBuilder.setTimestampField(timeField.v1())
                .setTimestampFormats(timeField.v2().dateFormats)
                .setNeedClientTimezone(timeField.v2().hasTimezoneDependentParsing());
        }

        Tuple<SortedMap<String, Object>, SortedMap<String, FieldStats>> mappingsAndFieldStats =
            FileStructureUtils.guessMappingsAndCalculateFieldStats(explanation, sampleRecords);

        if (mappingsAndFieldStats.v2() != null) {
            structureBuilder.setFieldStats(mappingsAndFieldStats.v2());
        }

        SortedMap<String, Object> innerMappings = mappingsAndFieldStats.v1();
        Map<String, Object> secondLevelProperties = new LinkedHashMap<>();
        secondLevelProperties.put(FileStructureUtils.MAPPING_TYPE_SETTING, "object");
        secondLevelProperties.put(FileStructureUtils.MAPPING_PROPERTIES_SETTING, innerMappings);
        SortedMap<String, Object> outerMappings = new TreeMap<>();
        outerMappings.put(topLevelTag, secondLevelProperties);
        if (timeField != null) {
            outerMappings.put(FileStructureUtils.DEFAULT_TIMESTAMP_FIELD,
                Collections.singletonMap(FileStructureUtils.MAPPING_TYPE_SETTING, "date"));
        }

        FileStructure structure = structureBuilder
            .setMappings(outerMappings)
            .setExplanation(explanation)
            .build();

        return new XmlFileStructureFinder(sampleMessages, structure);
    }

    private XmlFileStructureFinder(List<String> sampleMessages, FileStructure structure) {
        this.sampleMessages = Collections.unmodifiableList(sampleMessages);
        this.structure = structure;
    }

    @Override
    public List<String> getSampleMessages() {
        return sampleMessages;
    }

    @Override
    public FileStructure getStructure() {
        return structure;
    }

    private static int numNewlinesIn(String str) {
        return (int) str.chars().filter(c -> c == '\n').count();
    }

    private static Map<String, Object> docToMap(Document doc) {

        Map<String, Object> docAsMap = new LinkedHashMap<>();

        doc.getDocumentElement().normalize();
        addNodeToMap(doc.getDocumentElement(), docAsMap);

        return docAsMap;
    }

    private static void addNodeToMap(Node node, Map<String, Object> nodeAsMap) {

        NamedNodeMap attributes = node.getAttributes();
        for (int i = 0; i < attributes.getLength(); ++i) {
            Node attribute = attributes.item(i);
            nodeAsMap.put(attribute.getNodeName(), attribute.getNodeValue());
        }

        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); ++i) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                if (child.getChildNodes().getLength() == 1) {
                    Node grandChild = child.getChildNodes().item(0);
                    String value = grandChild.getNodeValue().trim();
                    if (value.isEmpty() == false) {
                        nodeAsMap.put(child.getNodeName(), value);
                    }
                } else {
                    Map<String, Object> childNodeAsMap = new LinkedHashMap<>();
                    addNodeToMap(child, childNodeAsMap);
                    if (childNodeAsMap.isEmpty() == false) {
                        nodeAsMap.put(child.getNodeName(), childNodeAsMap);
                    }
                }
            }
        }
    }
}