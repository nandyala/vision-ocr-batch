package com.visionocr.mapping;

import java.util.Locale;

/** Upper- or lower-cases a value. mode = UPPER | LOWER. */
public class CaseNormalizer implements FieldNormalizer {

    private String mode = "UPPER";

    public CaseNormalizer() {
    }

    public CaseNormalizer(String mode) {
        this.mode = mode;
    }

    @Override
    public String normalize(String value) {
        if (value == null) {
            return null;
        }
        return "LOWER".equalsIgnoreCase(mode) ? value.toLowerCase(Locale.ROOT) : value.toUpperCase(Locale.ROOT);
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
}
