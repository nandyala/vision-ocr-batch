package com.visionocr.domain;

/** Pipeline stages. entryStatus = status a document must have for the stage to pick it up. */
public enum Stage {
    INGEST(null),
    CLASSIFY(DocStatus.NEW),
    EXTRACT(DocStatus.CLASSIFIED),
    MAP(DocStatus.EXTRACTED);

    private final DocStatus entryStatus;

    Stage(DocStatus entryStatus) {
        this.entryStatus = entryStatus;
    }

    public DocStatus entryStatus() {
        return entryStatus;
    }
}
