package com.jsonsql.query;

/**
 * Thrown when a WHERE clause contains an expression construct that is parsed
 * by the SQL parser but not supported by the evaluator. This makes unsupported
 * predicates fail loudly instead of silently filtering out all rows.
 */
public class UnsupportedExpressionException extends RuntimeException {
    public UnsupportedExpressionException(String message) {
        super(message);
    }
}
