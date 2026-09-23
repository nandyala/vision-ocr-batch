package com.visionocr.mapping;

import java.util.regex.Pattern;

/** Generic regex replace, configurable from XML for one-off cleanups without new Java code. */
public class RegexReplaceNormalizer implements FieldNormalizer {

    private final Pattern pattern;
    private final String replacement;

    public RegexReplaceNormalizer(String regex, String replacement) {
        this.pattern = Pattern.compile(regex);
        this.replacement = replacement == null ? "" : replacement;
    }

    @Override
    public String normalize(String value) {
        return value == null ? null : pattern.matcher(value).replaceAll(replacement);
    }
}
