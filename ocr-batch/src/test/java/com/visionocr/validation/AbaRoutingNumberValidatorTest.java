package com.visionocr.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AbaRoutingNumberValidatorTest {

    private final AbaRoutingNumberValidator validator = new AbaRoutingNumberValidator();

    @Test
    void acceptsValidRoutingNumbers() {
        assertNull(validator.validate("021000021")); // JPMorgan Chase
        assertNull(validator.validate("121000248")); // Wells Fargo
        assertNull(validator.validate("011000138")); // Bank of America
    }

    @Test
    void rejectsBadChecksumAndLength() {
        assertEquals("ROUTING_CHECKSUM_FAILED", validator.validate("021000022"));
        assertEquals("ROUTING_NOT_9_DIGITS", validator.validate("02100002"));
        assertEquals("ROUTING_NOT_9_DIGITS", validator.validate(null));
        assertEquals("ROUTING_CHECKSUM_FAILED", validator.validate("000000000"));
    }
}
