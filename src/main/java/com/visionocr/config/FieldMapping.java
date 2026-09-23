package com.visionocr.config;

import com.visionocr.mapping.FieldNormalizer;
import com.visionocr.validation.FieldValidator;

import java.util.ArrayList;
import java.util.List;

/**
 * Optional rules for one field of a doc type (declared in doctypes/*.xml).
 * Fields returned by the Azure model that have no FieldMapping are still stored as-is
 * (see DocTypeConfig.includeUnmappedFields); a FieldMapping is only needed to rename a field,
 * clean it up, validate it, or make it required.
 */
public class FieldMapping {

    /** Field name as labeled in Document Intelligence Studio (e.g. "RoutingNumber"). */
    private String azureField;
    /** Name stored in DOC_FIELD / extracted_json. Defaults to azureField when not set. */
    private String canonicalField;
    private boolean required;
    /** Below this Azure confidence the field goes to review. 0 disables the check. */
    private double minConfidence;
    private List<FieldNormalizer> normalizers = new ArrayList<>();
    private List<FieldValidator> validators = new ArrayList<>();

    public String getAzureField() { return azureField; }
    public void setAzureField(String azureField) { this.azureField = azureField; }
    public String getCanonicalField() {
        return canonicalField == null || canonicalField.isBlank() ? azureField : canonicalField;
    }
    public void setCanonicalField(String canonicalField) { this.canonicalField = canonicalField; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public double getMinConfidence() { return minConfidence; }
    public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }
    public List<FieldNormalizer> getNormalizers() { return normalizers; }
    public void setNormalizers(List<FieldNormalizer> normalizers) { this.normalizers = normalizers; }
    public List<FieldValidator> getValidators() { return validators; }
    public void setValidators(List<FieldValidator> validators) { this.validators = validators; }
}
