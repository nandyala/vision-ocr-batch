package com.visionocr.ui.web;

import com.visionocr.ui.support.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/** Every API error becomes {"error": "..."} with a matching HTTP status, so the UI can show it. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /**
     * A file that is not there (optional brand assets such as /brand/logo.svg, browser probes such as
     * /.well-known/...). Normal - plain 404, no error log.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Void> noResource(NoResourceFoundException e) {
        log.debug("Not found: {}", e.getResourcePath());
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> badRequest(RuntimeException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException e) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "The file is too large");
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<Map<String, String>> unsupported(UnsupportedOperationException e) {
        return error(HttpStatus.NOT_IMPLEMENTED, e.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, String>> database(DataAccessException e) {
        log.error("Database error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Database error: " + e.getMostSpecificCause().getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> other(Exception e) {
        log.error("API error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong: " + e.getMessage());
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message == null ? status.getReasonPhrase() : message));
    }
}
