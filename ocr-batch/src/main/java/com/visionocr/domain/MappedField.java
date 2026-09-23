package com.visionocr.domain;

/** One extracted field after mapping, normalization and validation. */
public class MappedField {

    private final String name;
    private String azureField;
    private String value;
    private String rawValue;
    private Double confidence;
    /** True when the field is declared in the doc type XML (rules applied), false for pass-through fields. */
    private boolean configured;
    private FieldStatus status = FieldStatus.OK;
    private String message;

    public MappedField(String name) {
        this.name = name;
    }

    public boolean hasValue() {
        return value != null && !value.isBlank();
    }

    public String getName() { return name; }
    public String getAzureField() { return azureField; }
    public void setAzureField(String azureField) { this.azureField = azureField; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getRawValue() { return rawValue; }
    public void setRawValue(String rawValue) { this.rawValue = rawValue; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public boolean isConfigured() { return configured; }
    public void setConfigured(boolean configured) { this.configured = configured; }
    public FieldStatus getStatus() { return status; }
    public void setStatus(FieldStatus status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
