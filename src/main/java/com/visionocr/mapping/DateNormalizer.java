package com.visionocr.mapping;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Converts dates to ISO yyyy-MM-dd. Values already in ISO form (Azure typed dates) pass through.
 * Unparseable values are returned unchanged so a validator can flag them.
 */
public class DateNormalizer implements FieldNormalizer {

    private final List<DateTimeFormatter> formats = new ArrayList<>();

    public DateNormalizer() {
        this(List.of("yyyy-MM-dd", "M/d/yyyy", "M/d/yy", "MM-dd-yyyy", "MMM d, yyyy", "MMMM d, yyyy", "d MMM yyyy"));
    }

    public DateNormalizer(List<String> patterns) {
        for (String p : patterns) {
            formats.add(DateTimeFormatter.ofPattern(p, Locale.US));
        }
    }

    @Override
    public String normalize(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        String v = value.trim().replaceAll("\\s+", " ");
        for (DateTimeFormatter f : formats) {
            try {
                return LocalDate.parse(v, f).toString();
            } catch (DateTimeParseException ignored) {
                // try next pattern
            }
        }
        return value;
    }
}
