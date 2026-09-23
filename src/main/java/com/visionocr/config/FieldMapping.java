package com.visionocr.config;

import com.visionocr.mapping.FieldNormalizer;
import com.visionocr.validation.FieldValidator;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps one field produced by the Azure model to a canonical field of the doc type,
 * with its normalization and validation rules. Declared in doctypes/*.xml.
 */
public class FieldMapping {

    /** Field name as labeled in Document Intelligence Studio (e.g. "RoutingNumber"). */
    private String azureField;
    /** Name used in DOC_FIELD and downstream systems (e.g. "routingNumber"). */
    private String canonicalField;
    private boolean required;
    /** Below this Azure confidence the field goes to review. 0 disables the check. */
    private double minConfidence;
    /** Sensitive values are encrypted at rest and masked everywhere else. */
    private boolean sensitive;
    private List<FieldNormalizer> normalizers = new ArrayList<>();
    private List<FieldValidator> validators = new ArrayList<>();

    public String getAzureField() { return azureField; }
    public void setAzureField(String azureField) { this.azureField = azureField; }
    public String getCanonicalField() { return canonicalField; }
    public void setCanonicalField(String canonicalField) { this.canonicalField = canonicalField; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public double getMinConfidence() { return minConfidence; }
    public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }
    public boolean isSensitive() { return sensitive; }
    public void setSensitive(boolean sensitive) { this.sensitive = sensitive; }
    public List<FieldNormalizer> getNormalizers() { return normalizers; }
    public void setNormalizers(List<FieldNormalizer> normalizers) { this.normalizers = normalizers; }
    public List<FieldValidator> getValidators() { return validators; }
    public void setValidators(List<FieldValidator> validators) { this.validators = validators; }
}
