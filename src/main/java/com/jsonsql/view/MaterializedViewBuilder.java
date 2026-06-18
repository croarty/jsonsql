package com.jsonsql.view;

import com.fasterxml.jackson.databind.JsonNode;
import com.jsonsql.config.MappingManager;
import com.jsonsql.index.IndexManager;
import com.jsonsql.query.ParsedQuery;
import com.jsonsql.query.QueryExecutor;
import com.jsonsql.query.QueryParseException;
import com.jsonsql.query.QueryParser;
import com.jsonsql.query.TableFileResolver;

import java.io.File;
import java.time.Instant;
import java.util.List;

/**
 * Builds and persists materialized views from a WITH query.
 */
public class MaterializedViewBuilder {
    public static final String INDEX_JSON_PATH_MARKER = "@materialized-view";

    private final MappingManager mappingManager;
    private final File dataDirectory;
    private final MaterializedViewManager viewManager;
    private final MaterializedViewStore viewStore;
    private final QueryParser queryParser;

    public MaterializedViewBuilder(MappingManager mappingManager, File dataDirectory,
                                   MaterializedViewManager viewManager,
                                   MaterializedViewStore viewStore) {
        this.mappingManager = mappingManager;
        this.dataDirectory = dataDirectory;
        this.viewManager = viewManager;
        this.viewStore = viewStore;
        this.queryParser = new QueryParser();
    }

    /**
     * Parse {@code fullSql}, extract the named CTE body, execute it, and persist as a view.
     */
    public MaterializedViewDefinition materialize(String name, String fullSql,
                                                   IndexManager indexManager) throws Exception {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("View name cannot be empty");
        }
        if (mappingManager.hasMapping(name)) {
            throw new IllegalArgumentException(
                "Cannot materialize view '" + name + "': a table mapping with that name already exists.");
        }
        if (viewManager.hasView(name)) {
            throw new IllegalArgumentException(
                "Materialized view already exists: " + name + ". Drop it first or use --rebuild-view.");
        }

        ParsedQuery parsed = queryParser.parse(fullSql);
        if (!parsed.hasCTEs() || !parsed.hasCteNamed(name)) {
            throw new QueryParseException(
                "Query must contain WITH " + name + " AS (...). CTE '" + name + "' was not found.");
        }

        String cteSql = queryParser.extractCteSql(fullSql, name);
        ParsedQuery cteBody = parsed.getCteNamed(name);

        QueryExecutor executor = QueryExecutor.forMaterialization(
            mappingManager, dataDirectory, null, indexManager, viewManager);
        List<JsonNode> rows = executor.executeToRowList(cteBody);

        TableFileResolver resolver = new TableFileResolver(mappingManager, dataDirectory);
        String fingerprint = SourceFingerprint.forParsedQuery(cteBody, mappingManager, resolver);

        viewStore.save(name, rows);

        MaterializedViewDefinition def = new MaterializedViewDefinition();
        def.setName(name);
        def.setCteSql(cteSql);
        def.setSourceFingerprint(fingerprint);
        def.setDataDirectory(TableFileResolver.canonicalPath(dataDirectory));
        def.setBuiltAt(Instant.now().toString());
        def.setRowCount(rows.size());
        viewManager.putView(def);
        return def;
    }

    public MaterializedViewDefinition rebuild(String name, IndexManager indexManager) throws Exception {
        MaterializedViewDefinition existing = viewManager.getView(name);
        if (existing == null) {
            throw new IllegalArgumentException("No materialized view: " + name);
        }
        viewManager.dropView(name);
        viewStore.delete(name);
        String wrapped = "WITH " + name + " AS (" + existing.getCteSql() + ") SELECT * FROM " + name;
        return materialize(name, wrapped, indexManager);
    }
}
