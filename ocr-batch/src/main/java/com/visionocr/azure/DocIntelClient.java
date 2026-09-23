package com.visionocr.azure;

import com.visionocr.domain.ClassificationOutput;
import com.visionocr.domain.ExtractionOutput;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Our own seam over Azure Document Intelligence. Batch components depend on this,
 * never on the Azure SDK, so they can be tested with an offline stub.
 */
public interface DocIntelClient {

    /** Classifier id used by {@link #classify}, recorded in DOC_AZURE_RESULT. */
    String classifierId();

    /** Runs the custom classifier (split mode auto). One entry per document found in the file. */
    ClassificationOutput classify(Path file);

    /** Runs a custom extraction model. pages may be null (all pages) or e.g. "1-2". */
    ExtractionOutput analyze(String modelId, Path file, String pages);

    /** Custom models on the resource: model id -> description (prebuilt models are left out). Used by the demo UI. */
    default Map<String, String> listCustomModels() {
        throw new UnsupportedOperationException("Model listing is not supported by " + getClass().getSimpleName());
    }

    /** Fields a model returns: field name -> field type (string, date, signature...). Used by the demo UI. */
    default Map<String, String> modelFields(String modelId) {
        throw new UnsupportedOperationException("Model details are not supported by " + getClass().getSimpleName());
    }

    /** Convenience for callers that only need the names. */
    default List<String> modelFieldNames(String modelId) {
        return List.copyOf(modelFields(modelId).keySet());
    }
}
