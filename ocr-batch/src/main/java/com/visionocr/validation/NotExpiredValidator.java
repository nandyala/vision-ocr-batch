package com.visionocr.validation;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** yyyy-MM-dd date (use after norm.date) that is today or later, e.g. an ID's expiry date. */
public class NotExpiredValidator implements FieldValidator {

    @Override
    public String validate(String value) {
        try {
            return LocalDate.parse(value == null ? "" : value.trim()).isBefore(LocalDate.now()) ? "EXPIRED" : null;
        } catch (DateTimeParseException e) {
            return "NOT_A_DATE";
        }
    }
}
