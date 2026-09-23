package com.visionocr.domain;

import java.util.List;

/** Classifier response: the documents found in the file plus the raw Azure JSON. */
public class ClassificationOutput {

    private final List<ClassificationResult> documents;
    private final String rawResultJson;

    public ClassificationOutput(List<ClassificationResult> documents, String rawResultJson) {
        this.documents = documents;
        this.rawResultJson = rawResultJson;
    }

    public List<ClassificationResult> getDocuments() { return documents; }
    public String getRawResultJson() { return rawResultJson; }
}
