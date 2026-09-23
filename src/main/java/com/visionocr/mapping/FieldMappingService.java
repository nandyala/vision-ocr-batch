package com.visionocr.mapping;

import com.visionocr.config.DocTypeConfig;
import com.visionocr.config.FieldMapping;
import com.visionocr.domain.DocumentRecord;
import com.visionocr.domain.ExtractionOutput;
import com.visionocr.domain.FieldStatus;
import com.visionocr.domain.MappedDocument;
import com.visionocr.domain.MappedField;
import com.visionocr.domain.RawField;
import com.visionocr.validation.CrossFieldRule;
import com.visionocr.validation.FieldValidator;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Generic, config-driven mapping. Works for every doc type: it only reads the DocTypeConfig.
 * Adds review reasons to the record; the caller decides COMPLETED vs REVIEW.
 */
public class FieldMappingService {

    public MappedDocument map(DocTypeConfig config, DocumentRecord record, ExtractionOutput output, Long resultId) {
        MappedDocument result = new MappedDocument(record, resultId);
        Map<String, MappedField> byName = new LinkedHashMap<>();

        if (output.getDocConfidence() != null && output.getDocConfidence() < config.getMinDocumentConfidence()) {
            record.addReviewReason("LOW_DOC_CONFIDENCE");
        }

        for (FieldMapping fm : config.getFields()) {
            MappedField mf = mapField(fm, output.getFields().get(fm.getAzureField()));
            byName.put(fm.getCanonicalField(), mf);
            result.getFields().add(mf);

            switch (mf.getStatus()) {
                case MISSING:
                    if (fm.isRequired()) {
                        record.addReviewReason("MISSING:" + fm.getCanonicalField());
                    }
                    break;
                case LOW_CONFIDENCE:
                    record.addReviewReason("LOW_CONFIDENCE:" + fm.getCanonicalField());
                    break;
                case INVALID:
                    record.addReviewReason("INVALID:" + fm.getCanonicalField() + ":" + mf.getMessage());
                    break;
                default:
                    break;
            }
        }

        for (CrossFieldRule rule : config.getCrossFieldRules()) {
            record.addReviewReason(rule.check(byName));
        }
        return result;
    }

    MappedField mapField(FieldMapping fm, RawField raw) {
        MappedField mf = new MappedField(fm.getCanonicalField());
        mf.setSensitive(fm.isSensitive());

        String value = raw == null ? null : raw.bestValue();
        for (FieldNormalizer n : fm.getNormalizers()) {
            value = n.normalize(value);
        }
        mf.setValue(value == null || value.isBlank() ? null : value);
        mf.setConfidence(raw == null ? null : raw.getConfidence());

        if (!mf.hasValue()) {
            mf.setStatus(FieldStatus.MISSING);
            return mf;
        }
        for (FieldValidator v : fm.getValidators()) {
            String error = v.validate(mf.getValue());
            if (error != null) {
                mf.setStatus(FieldStatus.INVALID);
                mf.setMessage(error);
                return mf;
            }
        }
        if (fm.getMinConfidence() > 0 && mf.getConfidence() != null && mf.getConfidence() < fm.getMinConfidence()) {
            mf.setStatus(FieldStatus.LOW_CONFIDENCE);
            mf.setMessage(String.format("%.2f < %.2f", mf.getConfidence(), fm.getMinConfidence()));
        }
        return mf;
    }
}
