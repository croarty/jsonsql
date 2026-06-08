package com.jsonsql.query;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Result of a dry-run validation: parsed query structure and resolved physical table names.
 */
public class DryRunResult {
    private final ParsedQuery parsedQuery;
    private final Set<String> resolvedTables;

    public DryRunResult(ParsedQuery parsedQuery, Set<String> resolvedTables) {
        this.parsedQuery = parsedQuery;
        this.resolvedTables = new LinkedHashSet<>(resolvedTables);
    }

    public ParsedQuery getParsedQuery() {
        return parsedQuery;
    }

    public Set<String> getResolvedTables() {
        return resolvedTables;
    }
}
