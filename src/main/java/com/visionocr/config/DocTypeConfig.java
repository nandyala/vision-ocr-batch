package com.visionocr.config;

import com.visionocr.validation.CrossFieldRule;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the pipeline needs to know about one document type.
 * One XML file per doc type in src/main/resources/doctypes/ - adding a doc type
 * never requires a Java change unless it needs a brand-new normalizer/validator.
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
    private List<FieldMapping> fields = new ArrayList<>();
    private List<CrossFieldRule> crossFieldRules = new ArrayList<>();

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
    public List<CrossFieldRule> getCrossFieldRules() { return crossFieldRules; }
    public void setCrossFieldRules(List<CrossFieldRule> crossFieldRules) { this.crossFieldRules = crossFieldRules; }
}
