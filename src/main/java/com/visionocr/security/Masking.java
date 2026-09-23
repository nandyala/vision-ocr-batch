package com.visionocr.security;

/** "021000021" -> "*****0021". Used for display_value and anything that could reach logs. */
public final class Masking {

    private Masking() {
    }

    public static String mask(String value) {
        if (value == null) {
            return null;
        }
        int keep = value.length() > 8 ? 4 : Math.min(2, value.length() / 2);
        return "*".repeat(value.length() - keep) + value.substring(value.length() - keep);
    }
}
