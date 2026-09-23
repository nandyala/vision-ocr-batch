package com.visionocr.mapping;

/** Cleans a raw OCR value. Normalizers are chained in the order declared in the doc type XML. */
public interface FieldNormalizer {
    String normalize(String value);
}
