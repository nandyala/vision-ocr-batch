package com.visionocr.validation;

/** Validates a normalized value. Returns null when valid, otherwise a short reason code/message. */
public interface FieldValidator {
    String validate(String value);
}
