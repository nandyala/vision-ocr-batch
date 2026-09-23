package com.visionocr.mapping;

import java.math.BigDecimal;

/** "$1,234.50" -> "1234.50". Unparseable values are returned unchanged. */
public class AmountNormalizer implements FieldNormalizer {
    @Override
    public String normalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String v = value.replaceAll("[^0-9.\\-]", "");
        try {
            return new BigDecimal(v).toPlainString();
        } catch (NumberFormatException e) {
            return value;
        }
    }
}
