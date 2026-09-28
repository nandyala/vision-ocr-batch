package com.visionocr.ui.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiAccessFilterTest {

    @Test
    void keysAndStatuses() {
        ApiAccessFilter f = new ApiAccessFilter(Map.of("crm", "k".repeat(24), "short", "tooShort", "bad name", "x".repeat(30)));
        assertEquals("crm", f.clientFor("k".repeat(24)));
        assertNull(f.clientFor("k".repeat(23)));
        assertNull(f.clientFor("tooShort"));                  // under 24 characters: ignored
        assertNull(f.clientFor("x".repeat(30)));              // invalid client name: ignored
        assertNull(f.clientFor(null));
        assertEquals("IN_REVIEW", ExternalApiController.external("REVIEW"));
        assertEquals("PROCESSING", ExternalApiController.external("ERROR"));
        assertEquals("PROCESSING", ExternalApiController.external(null));
    }
}
