package com.visionocr.ui.support;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/** Reviewer name sent by the browser in the X-User header (demo: no login, the user types a name). */
public final class Reviewer {

    public static final String HEADER = "X-User";

    private Reviewer() {
    }

    public static String of(String header) {
        String u = header == null ? "" : URLDecoder.decode(header, StandardCharsets.UTF_8).trim();
        u = u.replaceAll("[^\\p{L}\\p{N} ._@'-]", "");
        return u.isEmpty() ? "demo-user" : u.substring(0, Math.min(u.length(), 100));
    }
}
