package com.visionocr.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Collects every {@link DocTypeConfig} bean in the context. Declared in XML with
 * autowire="constructor", so dropping a new doctypes/xyz.xml file on the classpath
 * is all it takes to register a new doc type.
 */
public class DocTypeRegistry {

    private static final Logger log = LoggerFactory.getLogger(DocTypeRegistry.class);

    private final Map<String, DocTypeConfig> byDocType = new LinkedHashMap<>();
    private final Map<String, DocTypeConfig> byClassifierLabel = new LinkedHashMap<>();

    public DocTypeRegistry(List<DocTypeConfig> configs) {
        for (DocTypeConfig c : configs) {
            if (c.getDocType() == null || c.getDocType().isBlank()) {
                throw new IllegalStateException("DocTypeConfig without docType");
            }
            String key = key(c.getDocType());
            if (byDocType.put(key, c) != null) {
                throw new IllegalStateException("Duplicate docType: " + c.getDocType());
            }
            for (String label : c.getClassifierLabels()) {
                DocTypeConfig prev = byClassifierLabel.put(label.toLowerCase(Locale.ROOT), c);
                if (prev != null) {
                    throw new IllegalStateException("Classifier label '" + label + "' mapped to both "
                            + prev.getDocType() + " and " + c.getDocType());
                }
            }
            log.info("Registered doc type {} (model={}, labels={}, fields={}, enabled={})",
                    c.getDocType(), c.getModelId(), c.getClassifierLabels(), c.getFields().size(), c.isEnabled());
        }
    }

    public DocTypeConfig get(String docType) {
        return docType == null ? null : byDocType.get(key(docType));
    }

    public boolean contains(String docType) {
        return get(docType) != null;
    }

    public DocTypeConfig findByClassifierLabel(String label) {
        return label == null ? null : byClassifierLabel.get(label.toLowerCase(Locale.ROOT));
    }

    public Collection<DocTypeConfig> all() {
        return Collections.unmodifiableCollection(byDocType.values());
    }

    private static String key(String docType) {
        return docType.trim().toUpperCase(Locale.ROOT);
    }
}
