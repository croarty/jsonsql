package com.jsonsql.query;

/**
 * Thrown in strict mode when the data holds values a calculation can't use, such as text
 * where SUM needs a number. Without strict mode the same thing is a warning.
 */
public class StrictModeException extends RuntimeException {
    public StrictModeException(String message) {
        super(message);
    }
}
