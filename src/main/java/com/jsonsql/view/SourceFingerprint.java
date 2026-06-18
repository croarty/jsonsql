package com.jsonsql.view;

import com.jsonsql.config.MappingManager;
import com.jsonsql.query.JoinInfo;
import com.jsonsql.query.ParsedQuery;
import com.jsonsql.query.QueryParser;
import com.jsonsql.query.TableFileResolver;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Computes freshness fingerprints from backing JSON file metadata (mtime + size).
 * Paths in fingerprints are relative to the data directory so freshness is stable
 * across different {@code --data-dir} values at list/query time.
 */
public final class SourceFingerprint {

    private SourceFingerprint() {
    }

    /**
     * Fingerprint for all mapped tables referenced by a parsed query (FROM + JOINs).
     */
    public static String forParsedQuery(ParsedQuery query, MappingManager mappingManager,
                                        TableFileResolver resolver) {
        return forTables(collectTableNames(query), mappingManager, resolver);
    }

    public static String forCteSql(String cteSql, MappingManager mappingManager,
                                   TableFileResolver resolver) throws Exception {
        ParsedQuery body = new QueryParser().parse(cteSql);
        return forParsedQuery(body, mappingManager, resolver);
    }

    public static String forTables(Set<String> tableNames, MappingManager mappingManager,
                                   TableFileResolver resolver) {
        StringBuilder sb = new StringBuilder();
        for (String table : new TreeSet<>(tableNames)) {
            if (mappingManager.hasMapping(table)) {
                appendMappingFiles(sb, resolver, table);
            }
        }
        return sb.toString();
    }

    private static void appendMappingFiles(StringBuilder sb, TableFileResolver resolver, String table) {
        try {
            File dataDir = resolver.getDataDirectory();
            for (File f : resolver.resolveFiles(table)) {
                String rel = TableFileResolver.relativePath(dataDir, f);
                sb.append(rel).append('@')
                    .append(f.lastModified()).append(':').append(f.length()).append(';');
            }
        } catch (IOException ignored) {
            // omit from fingerprint on resolution failure
        }
    }

    public static Set<String> collectTableNames(ParsedQuery query) {
        Set<String> names = new LinkedHashSet<>();
        if (query.getFromTable() != null && query.getFromTable().getTableName() != null) {
            names.add(query.getFromTable().getTableName());
        }
        if (query.hasJoins()) {
            for (JoinInfo join : query.getJoins()) {
                if (join.getTable() != null && join.getTable().getTableName() != null) {
                    names.add(join.getTable().getTableName());
                }
            }
        }
        return names;
    }

    /**
     * Data directory used to evaluate freshness: the path stored at build time, or the
     * CLI {@code --data-dir} for legacy views created before {@code dataDirectory} was persisted.
     */
    public static File resolveFreshnessDirectory(MaterializedViewDefinition def, File cliDataDirectory) {
        if (def != null && def.getDataDirectory() != null && !def.getDataDirectory().isBlank()) {
            return new File(def.getDataDirectory());
        }
        return cliDataDirectory;
    }

    public static boolean isFresh(MaterializedViewDefinition def, MappingManager mappingManager,
                                  File cliDataDirectory) {
        if (def == null || def.getSourceFingerprint() == null) {
            return false;
        }
        try {
            File dir = resolveFreshnessDirectory(def, cliDataDirectory);
            TableFileResolver resolver = new TableFileResolver(mappingManager, dir);
            String current = forCteSql(def.getCteSql(), mappingManager, resolver);
            return def.getSourceFingerprint().equals(current);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Warn when list/show {@code --data-dir} differs from the directory used at build time.
     */
    public static void warnIfDataDirectoryMismatch(MaterializedViewDefinition def, File cliDataDirectory) {
        if (def == null || def.getDataDirectory() == null || def.getDataDirectory().isBlank()) {
            return;
        }
        try {
            File stored = new File(def.getDataDirectory()).getCanonicalFile();
            File cli = cliDataDirectory.getCanonicalFile();
            if (!stored.equals(cli)) {
                System.err.println("Warning: --data-dir differs from the directory used to build view '"
                    + def.getName() + "'. Freshness is checked against the build-time directory.");
            }
        } catch (IOException ignored) {
            // skip warning on path resolution failure
        }
    }
}
