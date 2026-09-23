package com.visionocr.domain;

/** A successful Azure call waiting to be written to DOC_AZURE_RESULT (plain text; encrypted on write). */
public class AzureCallResult {

    public static final String API_VERSION = "2024-11-30";

    private final String operation;      // CLASSIFY | EXTRACT
    private final String modelId;
    private final Double docConfidence;
    private final String resultJson;
    private final String fieldsJson;
    private final long durationMs;

    public AzureCallResult(String operation, String modelId, Double docConfidence,
                           String resultJson, String fieldsJson, long durationMs) {
        this.operation = operation;
        this.modelId = modelId;
        this.docConfidence = docConfidence;
        this.resultJson = resultJson;
        this.fieldsJson = fieldsJson;
        this.durationMs = durationMs;
    }

    public String getOperation() { return operation; }
    public String getModelId() { return modelId; }
    public Double getDocConfidence() { return docConfidence; }
    public String getResultJson() { return resultJson; }
    public String getFieldsJson() { return fieldsJson; }
    public long getDurationMs() { return durationMs; }
}
