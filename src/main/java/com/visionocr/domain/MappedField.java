package com.visionocr.domain;

/** A canonical field after mapping, normalization and validation. */
public class MappedField {

    private String name;
    private String value;
    private Double confidence;
    private boolean sensitive;
    private FieldStatus status = FieldStatus.OK;
    private String message;

    public MappedField(String name) {
        this.name = name;
    }

    public boolean hasValue() {
        return value != null && !value.isBlank();
    }

    public String getName() { return name; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public boolean isSensitive() { return sensitive; }
    public void setSensitive(boolean sensitive) { this.sensitive = sensitive; }
    public FieldStatus getStatus() { return status; }
    public void setStatus(FieldStatus status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
