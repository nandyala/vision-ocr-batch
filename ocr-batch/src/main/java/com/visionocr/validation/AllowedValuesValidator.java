package com.visionocr.validation;

import java.util.List;

/** Value must be one of a fixed list (case-insensitive), e.g. signature must be "signed". */
public class AllowedValuesValidator implements FieldValidator {

    private final List<String> allowed;
    private final String message;

    public AllowedValuesValidator(List<String> allowed, String message) {
        this.allowed = allowed;
        this.message = message;
    }

    @Override
    public String validate(String value) {
        if (value != null) {
            for (String a : allowed) {
                if (a.equalsIgnoreCase(value.trim())) {
                    return null;
                }
            }
        }
        return message;
    }

    public List<String> getAllowed() {
        return allowed;
    }

    public String getMessage() {
        return message;
    }
}
