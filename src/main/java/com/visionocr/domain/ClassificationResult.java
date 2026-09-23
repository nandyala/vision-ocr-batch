package com.visionocr.domain;

/** One document found by the classifier inside a file (a file can contain several). */
public class ClassificationResult {

    private final String label;
    private final double confidence;
    /** Page range in Azure syntax, e.g. "1-2". Null when unknown. */
    private final String pages;

    public ClassificationResult(String label, double confidence, String pages) {
        this.label = label;
        this.confidence = confidence;
        this.pages = pages;
    }

    public String getLabel() { return label; }
    public double getConfidence() { return confidence; }
    public String getPages() { return pages; }
}
