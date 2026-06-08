package com.jsonsql.query;

import com.jsonsql.config.MappingManager;

/**
 * Formats dry-run validation output for the CLI.
 */
public final class DryRunReporter {

    private DryRunReporter() {
    }

    public static String format(DryRunResult result, MappingManager mappingManager) {
        StringBuilder sb = new StringBuilder();
        sb.append("Dry run OK.\n");
        sb.append("  SQL parsed successfully.\n");

        if (result.getParsedQuery().hasCTEs()) {
            sb.append("  CTEs: ")
                .append(String.join(", ", result.getParsedQuery().getCommonTableExpressions().keySet()))
                .append('\n');
        }

        if (result.getResolvedTables().isEmpty()) {
            sb.append("  No physical tables referenced.");
            return sb.toString().stripTrailing();
        }

        sb.append("  Tables resolved: ")
            .append(String.join(", ", result.getResolvedTables()))
            .append('\n');

        for (String table : result.getResolvedTables()) {
            sb.append("    ")
                .append(table)
                .append(" -> ")
                .append(mappingManager.getJsonPath(table))
                .append('\n');
        }

        return sb.toString().stripTrailing();
    }
}
