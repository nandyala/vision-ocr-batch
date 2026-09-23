package com.visionocr.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Collects every {@link DocTypeConfig} bean in the context. Declared in XML with
 * autowire="constructor", so dropping a new doctypes/xyz.xml file on the classpath (or in the
 * external doctypes.dir folder) is all it takes to register a new doc type.
 * <p>
 * Thread-safe: a long-running process (the demo UI) can add or replace doc types at runtime with
 * {@link #register}; readers always see a consistent snapshot.
 */
public class DocTypeRegistry {

    private static final Logger log = LoggerFactory.getLogger(DocTypeRegistry.class);

    private volatile Map<String, DocTypeConfig> byDocType = Map.of();
    private volatile Map<String, DocTypeConfig> byClassifierLabel = Map.of();

    public DocTypeRegistry(List<DocTypeConfig> configs) {
        rebuild(configs);
        for (DocTypeConfig c : configs) {
            log.info("Registered doc type {} (model={}, labels={}, fields={}, enabled={})",
                    c.getDocType(), c.getModelId(), c.getClassifierLabels(), c.getFields().size(), c.isEnabled());
        }
    }

    /**
     * Adds a doc type at runtime. With replace=true an existing doc type with the same name is replaced
     * (used when a doc type created in the UI is edited). Fails on duplicate names or classifier labels.
     */
    public synchronized void register(DocTypeConfig config, boolean replace) {
        List<DocTypeConfig> next = new ArrayList<>();
        for (DocTypeConfig c : byDocType.values()) {
            if (key(c.getDocType()).equals(key(config.getDocType()))) {
                if (!replace) {
                    throw new IllegalStateException("Duplicate docType: " + config.getDocType());
                }
                continue;
            }
            next.add(c);
        }
        next.add(config);
        rebuild(next);
        log.info("Doc type {} {} at runtime (model={}, fields={})", config.getDocType(),
                replace ? "registered/replaced" : "registered", config.getModelId(), config.getFields().size());
    }

    private synchronized void rebuild(List<DocTypeConfig> configs) {
        Map<String, DocTypeConfig> types = new LinkedHashMap<>();
        Map<String, DocTypeConfig> labels = new LinkedHashMap<>();
        for (DocTypeConfig c : configs) {
            if (c.getDocType() == null || c.getDocType().isBlank()) {
                throw new IllegalStateException("DocTypeConfig without docType");
            }
            if (types.put(key(c.getDocType()), c) != null) {
                throw new IllegalStateException("Duplicate docType: " + c.getDocType());
            }
            for (String label : c.getClassifierLabels()) {
                DocTypeConfig prev = labels.put(label.toLowerCase(Locale.ROOT), c);
                if (prev != null) {
                    throw new IllegalStateException("Classifier label '" + label + "' mapped to both "
                            + prev.getDocType() + " and " + c.getDocType());
                }
            }
        }
        byDocType = Collections.unmodifiableMap(types);
        byClassifierLabel = Collections.unmodifiableMap(labels);
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
        return byDocType.values();
    }

    private static String key(String docType) {
        return docType.trim().toUpperCase(Locale.ROOT);
    }
}
