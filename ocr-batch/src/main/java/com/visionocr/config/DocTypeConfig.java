package com.visionocr.config;

import com.visionocr.validation.CrossFieldRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the pipeline needs to know about one document type.
 * One XML file per doc type in src/main/resources/doctypes/. Minimum: docType, classifierLabels
 * (if a classifier is used) and modelId - every field the model returns is then stored as-is.
 * Add FieldMappings only for fields that need renaming, cleanup, validation or are required.
 */
public class DocTypeConfig {

    private String docType;
    private String description;
    private boolean enabled = true;
    /** Class names in the Azure custom classifier that map to this doc type. */
    private List<String> classifierLabels = new ArrayList<>();
    /** Azure custom extraction model (neural, template, or composed) used for this doc type. */
    private String modelId;
    private double minClassifyConfidence = 0.70;
    private double minDocumentConfidence = 0.0;
    /** Optional per-field rules. Fields without rules are still stored when includeUnmappedFields = true. */
    private List<FieldMapping> fields = new ArrayList<>();
    private List<CrossFieldRule> crossFieldRules = new ArrayList<>();
    /** Store every field the model returns, not only the ones declared in {@link #fields}. */
    private boolean includeUnmappedFields = true;
    /** Minimum confidence for fields without rules (0 = no check). */
    private double defaultMinConfidence = 0.0;
    /** Columns of the generated view v_doc_<doctype>. Empty = the fields listed in {@link #fields}. */
    private List<String> viewColumns = new ArrayList<>();

    public String getDocType() { return docType; }
    public void setDocType(String docType) { this.docType = docType; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public List<String> getClassifierLabels() { return classifierLabels; }
    public void setClassifierLabels(List<String> classifierLabels) { this.classifierLabels = classifierLabels; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public double getMinClassifyConfidence() { return minClassifyConfidence; }
    public void setMinClassifyConfidence(double minClassifyConfidence) { this.minClassifyConfidence = minClassifyConfidence; }
    public double getMinDocumentConfidence() { return minDocumentConfidence; }
    public void setMinDocumentConfidence(double minDocumentConfidence) { this.minDocumentConfidence = minDocumentConfidence; }
    public List<FieldMapping> getFields() { return fields; }
    public void setFields(List<FieldMapping> fields) { this.fields = fields; }
    public boolean isIncludeUnmappedFields() { return includeUnmappedFields; }
    public void setIncludeUnmappedFields(boolean includeUnmappedFields) { this.includeUnmappedFields = includeUnmappedFields; }
    public double getDefaultMinConfidence() { return defaultMinConfidence; }
    public void setDefaultMinConfidence(double defaultMinConfidence) { this.defaultMinConfidence = defaultMinConfidence; }
    public List<String> getViewColumns() { return viewColumns; }
    public void setViewColumns(List<String> viewColumns) { this.viewColumns = viewColumns; }
    public List<CrossFieldRule> getCrossFieldRules() { return crossFieldRules; }
    public void setCrossFieldRules(List<CrossFieldRule> crossFieldRules) { this.crossFieldRules = crossFieldRules; }
}
