package com.visionocr.mapping;

/** Collapses whitespace/newlines and strips stray leading/trailing punctuation such as ':' or '_'. */
public class WhitespaceNormalizer implements FieldNormalizer {
    @Override
    public String normalize(String value) {
        if (value == null) {
            return null;
        }
        String v = value.replaceAll("\\s+", " ").trim();
        v = v.replaceAll("^[\\s:;_,.\\-]+", "").replaceAll("[\\s:;_,\\-]+$", "");
        return v;
    }
}
