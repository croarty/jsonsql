package com.jsonsql.query;

/**
 * Exception thrown when query parsing fails.
 */
public class QueryParseException extends Exception {
    public QueryParseException(String message) {
        super(message);
    }

    public QueryParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

