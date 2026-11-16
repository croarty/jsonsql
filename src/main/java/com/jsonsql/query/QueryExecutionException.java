package com.jsonsql.query;

/**
 * The query is valid, but can't give an answer for this data, such as a subquery used as a
 * value that returns several rows. It is a query error, exit code 1.
 */
public class QueryExecutionException extends RuntimeException {

    public QueryExecutionException(String message) {
        super(message);
    }
}
