package com.visionocr.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/** Result of running an extraction model on one file. Serialized to DOC_JOB.RAW_FIELDS. */
public class ExtractionOutput {

    private String modelDocType;
    private Double docConfidence;
    private Map<String, RawField> fields = new LinkedHashMap<>();
    /**
     * Full Azure AnalyzeResult JSON. Not persisted in the DB (can be large); see RawResultStore.
     * Excluded from Jackson serialization via PROPAGATE_TRANSIENT_MARKER in {@link com.visionocr.util.Json}.
     */
    private transient String rawResultJson;

    public String getModelDocType() { return modelDocType; }
    public void setModelDocType(String modelDocType) { this.modelDocType = modelDocType; }
    public Double getDocConfidence() { return docConfidence; }
    public void setDocConfidence(Double docConfidence) { this.docConfidence = docConfidence; }
    public Map<String, RawField> getFields() { return fields; }
    public void setFields(Map<String, RawField> fields) { this.fields = fields; }

    public String getRawResultJson() { return rawResultJson; }
    public void setRawResultJson(String rawResultJson) { this.rawResultJson = rawResultJson; }
}
