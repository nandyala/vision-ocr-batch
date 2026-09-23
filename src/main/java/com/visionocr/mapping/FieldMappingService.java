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

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Generic, config-driven mapping that works for every doc type:
 * <ol>
 *   <li>Fields declared in the doc type XML: renamed, normalized, validated, checked for confidence.</li>
 *   <li>All other fields the model returned (when includeUnmappedFields = true): stored under their
 *       Azure name with whitespace cleaned up and the default confidence check.</li>
 * </ol>
 * Adds review reasons to the record; the caller decides COMPLETED vs REVIEW.
 */
public class FieldMappingService {

    private final FieldNormalizer passThroughNormalizer = new WhitespaceNormalizer();

    public MappedDocument map(DocTypeConfig config, DocumentRecord record, ExtractionOutput output, Long resultId) {
        MappedDocument result = new MappedDocument(record, resultId);
        Map<String, MappedField> byName = new LinkedHashMap<>();
        Set<String> handledAzureFields = new HashSet<>();

        if (output.getDocConfidence() != null && output.getDocConfidence() < config.getMinDocumentConfidence()) {
            record.addReviewReason("LOW_DOC_CONFIDENCE");
        }

        // 1) fields with rules
        for (FieldMapping fm : config.getFields()) {
            handledAzureFields.add(fm.getAzureField());
            MappedField mf = mapConfigured(fm, output.getFields().get(fm.getAzureField()));
            byName.put(mf.getName(), mf);
            switch (mf.getStatus()) {
                case MISSING:
                    if (fm.isRequired()) {
                        record.addReviewReason("MISSING:" + mf.getName());
                    }
                    break;
                case LOW_CONFIDENCE:
                    record.addReviewReason("LOW_CONFIDENCE:" + mf.getName());
                    break;
                case INVALID:
                    record.addReviewReason("INVALID:" + mf.getName() + ":" + mf.getMessage());
                    break;
                default:
                    break;
            }
        }

        // 2) every other field the model returned
        if (config.isIncludeUnmappedFields()) {
            for (RawField raw : output.getFields().values()) {
                if (handledAzureFields.contains(raw.getName()) || byName.containsKey(raw.getName())) {
                    continue;
                }
                MappedField mf = mapPassThrough(raw, config.getDefaultMinConfidence());
                byName.put(mf.getName(), mf);
                if (mf.getStatus() == FieldStatus.LOW_CONFIDENCE) {
                    record.addReviewReason("LOW_CONFIDENCE:" + mf.getName());
                }
            }
        }

        for (CrossFieldRule rule : config.getCrossFieldRules()) {
            record.addReviewReason(rule.check(byName));
        }
        result.getFields().addAll(byName.values());
        return result;
    }

    MappedField mapConfigured(FieldMapping fm, RawField raw) {
        MappedField mf = new MappedField(fm.getCanonicalField());
        mf.setAzureField(fm.getAzureField());
        mf.setConfigured(true);

        String value = raw == null ? null : raw.bestValue();
        mf.setRawValue(value);
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
        checkConfidence(mf, fm.getMinConfidence());
        return mf;
    }

    MappedField mapPassThrough(RawField raw, double minConfidence) {
        MappedField mf = new MappedField(raw.getName());
        mf.setAzureField(raw.getName());
        mf.setConfigured(false);
        mf.setRawValue(raw.bestValue());
        String value = passThroughNormalizer.normalize(raw.bestValue());
        mf.setValue(value == null || value.isBlank() ? null : value);
        mf.setConfidence(raw.getConfidence());
        if (!mf.hasValue()) {
            mf.setStatus(FieldStatus.MISSING);   // informational only: not required
            return mf;
        }
        checkConfidence(mf, minConfidence);
        return mf;
    }

    private static void checkConfidence(MappedField mf, double min) {
        if (min > 0 && mf.getConfidence() != null && mf.getConfidence() < min) {
            mf.setStatus(FieldStatus.LOW_CONFIDENCE);
            mf.setMessage(String.format("%.2f < %.2f", mf.getConfidence(), min));
        }
    }
}
