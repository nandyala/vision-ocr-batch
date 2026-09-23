package com.visionocr.ui.service;

import java.util.List;

/**
 * A doc type as edited in the UI's designer. Turned into a doctypes/*.xml file by {@link DocTypeXml}
 * (the same format as the built-in doc types, so it can be moved into the jar later).
 */
public record DocTypeSpec(
        String docType,
        String description,
        Boolean enabled,
        String modelId,
        List<String> classifierLabels,
        Double minClassifyConfidence,
        Double minDocumentConfidence,
        Boolean includeUnmappedFields,
        Double defaultMinConfidence,
        List<Field> fields) {

    /** Rules for one field. normalizers/validators are bean ids from the catalog (norm.*, val.*). */
    public record Field(
            String azureField,
            String canonicalField,
            Boolean required,
            Double minConfidence,
            List<String> normalizers,
            List<String> validators,
            String regex,
            String regexMessage,
            List<String> allowedValues,
            String allowedMessage) {
    }
}
