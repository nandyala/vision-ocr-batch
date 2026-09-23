package com.visionocr.domain;

import java.util.ArrayList;
import java.util.List;

/** Output of the map/validate step: the document plus its canonical fields. */
public class MappedDocument {

    private final DocumentRecord record;
    private final Long resultId;
    private final List<MappedField> fields = new ArrayList<>();

    public MappedDocument(DocumentRecord record, Long resultId) {
        this.record = record;
        this.resultId = resultId;
    }

    public DocumentRecord getRecord() { return record; }
    /** DOC_AZURE_RESULT row the fields came from. */
    public Long getResultId() { return resultId; }
    public List<MappedField> getFields() { return fields; }
}
