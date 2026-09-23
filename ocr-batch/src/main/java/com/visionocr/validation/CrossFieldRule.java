package com.visionocr.validation;

import com.visionocr.domain.MappedField;

import java.util.Map;

/** Rule over several canonical fields. Returns null when satisfied, otherwise a review reason. */
public interface CrossFieldRule {
    String check(Map<String, MappedField> fields);
}
