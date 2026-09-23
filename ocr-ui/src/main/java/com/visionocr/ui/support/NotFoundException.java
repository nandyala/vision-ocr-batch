package com.visionocr.ui.support;

/** Mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public NotFoundException(String message) {
        super(message);
    }
}
