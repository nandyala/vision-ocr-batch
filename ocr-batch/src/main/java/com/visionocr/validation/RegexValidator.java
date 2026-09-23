package com.visionocr.validation;

import java.util.regex.Pattern;

public class RegexValidator implements FieldValidator {

    private final Pattern pattern;
    private final String message;

    public RegexValidator(String regex, String message) {
        this.pattern = Pattern.compile(regex);
        this.message = message;
    }

    @Override
    public String validate(String value) {
        return value != null && pattern.matcher(value).matches() ? null : message;
    }

    public String getRegex() {
        return pattern.pattern();
    }

    public String getMessage() {
        return message;
    }
}
