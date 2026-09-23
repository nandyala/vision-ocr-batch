package com.visionocr.domain;

/** A field exactly as Azure Document Intelligence returned it (before normalization/validation). */
public class RawField {

    private String name;
    /** Typed value rendered as text (e.g. valueString, valueDate, "signed"). */
    private String value;
    /** Text Azure read from the page for this field. Used as a fallback when value is empty. */
    private String content;
    private Double confidence;
    private String type;

    public RawField() {
    }

    public RawField(String name, String value, String content, Double confidence, String type) {
        this.name = name;
        this.value = value;
        this.content = content;
        this.confidence = confidence;
        this.type = type;
    }

    /** Value if present, otherwise the OCR content. */
    public String bestValue() {
        return (value != null && !value.isBlank()) ? value : content;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}
