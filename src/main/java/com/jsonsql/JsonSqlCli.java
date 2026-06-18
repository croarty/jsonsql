package com.jsonsql;

import com.jsonsql.config.CacheManager;
import com.jsonsql.config.MappingManager;
import com.jsonsql.config.QueryManager;
import com.jsonsql.config.QueryParameterReplacer;
import com.jsonsql.index.FileSummary;
import com.jsonsql.index.IndexBuilder;
import com.jsonsql.index.IndexDefinition;
import com.jsonsql.index.IndexManager;
import com.jsonsql.index.IndexStore;
import com.jsonsql.index.TableIndex;
import com.jsonsql.view.MaterializedViewBuilder;
import com.jsonsql.view.MaterializedViewDefinition;
import com.jsonsql.view.MaterializedViewManager;
import com.jsonsql.view.MaterializedViewStore;
import com.jsonsql.view.SourceFingerprint;
import com.jsonsql.introspection.TableDescriber;
import com.jsonsql.output.OutputFormat;
import com.jsonsql.output.OutputHandler;
import com.jsonsql.query.DryRunReporter;
import com.jsonsql.query.QueryExecutor;
import com.jsonsql.query.TableFileResolver;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.*;
import java.util.concurrent.Callable;

@Command(
    name = "jsonsql",
    mixinStandardHelpOptions = true,
    version = "JsonSQL 1.4.0",
    description = "Query JSON files using SQL-like syntax"
)
public class JsonSqlCli implements Callable<Integer> {

    @Option(names = {"-q", "--query"}, description = "SQL query to execute")
    private String query;

    @Option(names = {"-d", "--data-dir"}, description = "Directory containing JSON files", defaultValue = ".")
    private File dataDirectory;

    @Option(names = {"-c", "--config"}, description = "Path to mapping configuration file", defaultValue = ".jsonsql-mappings.json")
    private File configFile;
    
    @Option(names = {"--queries-file"}, description = "Path to saved queries file", defaultValue = ".jsonsql-queries.json")
    private File queriesFile;

    @Option(names = {"-o", "--output"}, description = "Output file path (default: stdout)")
    private File outputFile;

    @Option(names = {"--clipboard"}, description = "Copy output to clipboard")
    private boolean clipboard;

    @Option(names = {"--pretty"}, description = "Pretty-print JSON output")
    private boolean prettyPrint;

    @Option(names = {"--format"}, description = "Output format: json (default) or csv", defaultValue = "json")
    private String outputFormat;

    @Option(names = {"--list-tables"}, description = "Show all configured JSONPath shortcuts")
    private boolean listTables;

    @Option(names = {"--add-mapping"}, description = "Add a new JSONPath mapping", arity = "2")
    private String[] addMapping;
    
    @Option(names = {"--save-query"}, description = "Save a query with a name", arity = "1")
    private String saveQueryName;
    
    @Option(names = {"--run-query"}, description = "Execute a saved query by name")
    private String runQueryName;
    
    @Option(names = {"--list-queries"}, description = "Show all saved queries")
    private boolean listQueries;
    
    @Option(names = {"--delete-query"}, description = "Delete a saved query")
    private String deleteQueryName;
    
    @Option(names = {"--param"}, description = "Parameter value for parameterized queries (format: key=value). Can be used multiple times.", arity = "1")
    private List<String> parameters = new ArrayList<>();
    
    @Option(names = {"--enable-cache"}, description = "Enable disk-based caching of parsed JSON data for faster subsequent queries")
    private boolean enableCache;
    
    @Option(names = {"--clear-cache"}, description = "Clear all cached data for mapped tables")
    private boolean clearCache;

    @Option(names = {"--describe"}, description = "Show field names, types, and sample values for a mapped table")
    private String describeTable;

    @Option(names = {"--dry-run"}, description = "Validate query syntax and table mappings without executing")
    private boolean dryRun;

    @Option(names = {"--indexes-file"}, description = "Path to declared-index definitions file", defaultValue = ".jsonsql-indexes.json")
    private File indexesFile;

    @Option(names = {"--add-index"}, description = "Declare and build an index on a table field (args: <table> <field>)", arity = "2")
    private String[] addIndex;

    @Option(names = {"--drop-index"}, description = "Remove a declared index (args: <table> <field>)", arity = "2")
    private String[] dropIndex;

    @Option(names = {"--list-indexes"}, description = "List declared indexes with fresh/stale status")
    private boolean listIndexes;

    @Option(names = {"--rebuild-index"}, description = "Rebuild one index (args: <table> <field>)", arity = "2")
    private String[] rebuildIndex;

    @Option(names = {"--rebuild-indexes"}, description = "Rebuild all declared indexes")
    private boolean rebuildIndexes;

    @Option(names = {"--no-index"}, description = "Bypass declared indexes for this query (force full scan)")
    private boolean noIndex;

    @Option(names = {"--views-file"}, description = "Path to materialized view definitions file", defaultValue = ".jsonsql-views.json")
    private File viewsFile;

    @Option(names = {"--materialize-view"}, description = "Create a materialized view from a WITH query (arg: view name)", arity = "1")
    private String materializeViewName;

    @Option(names = {"--list-materialized-views"}, description = "List materialized views with SQL and freshness")
    private boolean listMaterializedViews;

    @Option(names = {"--show-view"}, description = "Show one materialized view definition", arity = "1")
    private String showViewName;

    @Option(names = {"--drop-materialized-view"}, description = "Remove a materialized view", arity = "1")
    private String dropMaterializedViewName;

    @Option(names = {"--rebuild-view"}, description = "Rebuild one materialized view", arity = "1")
    private String rebuildViewName;

    @Option(names = {"--rebuild-views"}, description = "Rebuild all materialized views")
    private boolean rebuildViews;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new JsonSqlCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() {
        // Reject ambiguous combinations of mutually-exclusive action flags up front.
        String exclusivityError = checkActionExclusivity();
        if (exclusivityError != null) {
            System.err.println("Error: " + exclusivityError);
            return 1;
        }

        // Construct config-backed managers, surfacing corrupt-config errors as friendly messages.
        MappingManager mappingManager;
        QueryManager queryManager;
        try {
            mappingManager = new MappingManager(configFile);
            queryManager = new QueryManager(queriesFile);
        } catch (RuntimeException e) {
            System.err.println("Error loading configuration: " + e.getMessage());
            if (isDebugEnabled()) {
                e.printStackTrace();
            }
            return 1;
        }

        // Handle list-tables command
        if (listTables) {
            mappingManager.listMappings();
            return 0;
        }

        // Handle add-mapping command
        if (addMapping != null && addMapping.length == 2) {
            String alias = addMapping[0];
            String jsonPath = addMapping[1];
            try {
                mappingManager.addMapping(alias, jsonPath);
            } catch (RuntimeException e) {
                System.err.println("Error adding mapping: " + e.getMessage());
                if (isDebugEnabled()) {
                    e.printStackTrace();
                }
                return 1;
            }
            System.out.println("Mapping added: " + alias + " -> " + jsonPath);
            return 0;
        }
        
        // Handle clear-cache command
        if (clearCache) {
            String dataDirError = validateDataDirectory();
            if (dataDirError != null) {
                System.err.println("Error: " + dataDirError);
                return 1;
            }
            CacheManager cacheManager = new CacheManager(dataDirectory);
            int clearedCount = cacheManager.clearAllCaches(mappingManager, dataDirectory);
            System.out.println("Cache cleared: " + clearedCount + " file(s) removed from " + dataDirectory.getAbsolutePath());
            if (clearedCount == 0) {
                System.out.println("Note: No cache files found. Make sure --data-dir points to the correct directory.");
            }
            return 0;
        }
        
        // Handle list-queries command
        if (listQueries) {
            listSavedQueries(queryManager);
            return 0;
        }

        // Handle describe command
        if (describeTable != null) {
            String dataDirError = validateDataDirectory();
            if (dataDirError != null) {
                System.err.println("Error: " + dataDirError);
                return 1;
            }
            try {
                CacheManager cacheManager = enableCache ? new CacheManager(dataDirectory) : null;
                MaterializedViewManager viewManager = loadViewManagerQuietly();
                TableDescriber describer = new TableDescriber(mappingManager, dataDirectory,
                    cacheManager, viewManager);
                System.out.println(describer.describe(describeTable));
                return 0;
            } catch (IllegalArgumentException e) {
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (Exception e) {
                System.err.println("Error describing table: " + e.getMessage());
                if (isDebugEnabled()) {
                    e.printStackTrace();
                }
                return 1;
            }
        }
        
        // Handle index management commands
        if (addIndex != null || dropIndex != null || listIndexes
            || rebuildIndex != null || rebuildIndexes) {
            return handleIndexCommands(mappingManager);
        }

        // Handle materialized view commands
        if (materializeViewName != null || listMaterializedViews || showViewName != null
            || dropMaterializedViewName != null || rebuildViewName != null || rebuildViews) {
            return handleViewCommands(mappingManager);
        }

        // Handle save-query command
        if (saveQueryName != null) {
            if (query == null) {
                System.err.println("Error: --query must be provided when saving a query");
                return 1;
            }
            try {
                queryManager.saveQuery(saveQueryName, query);
                System.out.println("Query saved: " + saveQueryName);
                System.out.println("SQL: " + query);
                return 0;
            } catch (Exception e) {
                System.err.println("Error saving query: " + e.getMessage());
                return 1;
            }
        }
        
        // Handle delete-query command
        if (deleteQueryName != null) {
            try {
                queryManager.deleteQuery(deleteQueryName);
                System.out.println("Query deleted: " + deleteQueryName);
                return 0;
            } catch (Exception e) {
                System.err.println("Error deleting query: " + e.getMessage());
                return 1;
            }
        }
        
        // Handle run-query command
        if (runQueryName != null) {
            if (!queryManager.hasQuery(runQueryName)) {
                System.err.println("Error: Query not found: " + runQueryName);
                System.err.println("Use --list-queries to see all saved queries");
                return 1;
            }
            query = queryManager.getQuery(runQueryName);
            // Informational messages go to stderr so stdout carries only result data (pipe-friendly)
            System.err.println("Running saved query: " + runQueryName);
            System.err.println("SQL: " + query);
            // Fall through to execute the query
        }

        // Handle query execution
        if (query != null) {
            if (dryRun && (outputFile != null || clipboard)) {
                System.err.println("Warning: --dry-run ignores --output and --clipboard.");
            }
            String dataDirError = validateDataDirectory();
            if (dataDirError != null) {
                System.err.println("Error: " + dataDirError);
                return 1;
            }

            String result;
            try {
                // Parse and replace parameters if any
                Map<String, String> paramMap = parseParameters(parameters);
                if (!paramMap.isEmpty() || QueryParameterReplacer.hasParameters(query)) {
                    query = QueryParameterReplacer.replaceParameters(query, paramMap);
                    if (runQueryName != null) {
                        System.err.println("SQL (with parameters): " + query);
                    }
                }
                
                // Create CacheManager if caching is enabled
                CacheManager cacheManager = enableCache ? new CacheManager(dataDirectory) : null;
                IndexManager indexManager = noIndex ? null : loadIndexManagerQuietly();
                MaterializedViewManager viewManager = loadViewManagerQuietly();
                QueryExecutor executor = new QueryExecutor(mappingManager, dataDirectory, cacheManager,
                    indexManager, viewManager);
                if (dryRun) {
                    System.out.println(DryRunReporter.format(executor.dryRunValidate(query), mappingManager));
                    return 0;
                }
                result = executor.execute(query);
            } catch (IllegalArgumentException e) {
                // Parameter / mapping / query argument errors
                System.err.println("Error: " + e.getMessage());
                return 1;
            } catch (Exception e) {
                System.err.println("Error executing query: " + e.getMessage());
                if (isDebugEnabled()) {
                    e.printStackTrace();
                }
                return 1;
            }

            // Output handling has its own error reporting, distinct from query execution
            try {
                OutputFormat format = parseOutputFormat(outputFormat);
                OutputHandler outputHandler = new OutputHandler(prettyPrint, format);
                outputHandler.handleOutput(result, outputFile, clipboard);
            } catch (Exception e) {
                System.err.println("Error writing output: " + e.getMessage());
                if (isDebugEnabled()) {
                    e.printStackTrace();
                }
                return 1;
            }

            return 0;
        }

        // No valid command provided
        System.err.println("No action specified. Provide one of:");
        System.err.println("  --query <sql>           Execute an inline SQL query");
        System.err.println("  --run-query <name>      Execute a saved query");
        System.err.println("  --save-query <name> --query <sql>   Save a query");
        System.err.println("  --delete-query <name>   Delete a saved query");
        System.err.println("  --list-tables           List configured mappings");
        System.err.println("  --describe <table>      Show table fields, types, and samples");
        System.err.println("  --list-queries          List saved queries");
        System.err.println("  --add-mapping <a> <p>   Add a JSONPath mapping");
        System.err.println("  --clear-cache           Clear cached data");
        System.err.println("  --add-index <table> <field>   Declare and build an index");
        System.err.println("  --list-indexes          List declared indexes");
        System.err.println("  --materialize-view <name> --query <sql>   Persist a WITH query as a view");
        System.err.println("  --list-materialized-views   List materialized views");
        System.err.println("  --show-view <name>      Show one materialized view");
        System.err.println("  --rebuild-view <name>   Rebuild a materialized view");
        System.err.println("  (Use -h or --help for the full option list)");
        return 1;
    }

    /**
     * Determine whether DEBUG diagnostics (stack traces) should be printed.
     * Enabled only when the DEBUG env var holds a truthy value; values like
     * "", "0", "false", "no", "off" are treated as disabled.
     */
    static boolean isDebugEnabled() {
        String debug = System.getenv("DEBUG");
        if (debug == null) {
            return false;
        }
        String v = debug.trim().toLowerCase();
        return !(v.isEmpty() || v.equals("0") || v.equals("false") || v.equals("no") || v.equals("off"));
    }

    /**
     * Validate that the configured data directory exists and is a readable directory.
     * Returns an error message, or null when valid.
     */
    private String validateDataDirectory() {
        if (dataDirectory == null) {
            return "Data directory is not specified";
        }
        if (!dataDirectory.exists()) {
            return "Data directory does not exist: " + dataDirectory.getAbsolutePath();
        }
        if (!dataDirectory.isDirectory()) {
            return "Data directory is not a directory: " + dataDirectory.getAbsolutePath();
        }
        return null;
    }

    /**
     * Detect mutually-exclusive action flags. Returns an error message when more than one
     * primary action is requested, or null when the combination is valid.
     * Note: --save-query consumes --query as its payload, and --run-query populates the query,
     * so those pairings are handled specially rather than counted as two separate actions.
     */
    private String checkActionExclusivity() {
        int actions = 0;
        if (listTables) actions++;
        if (addMapping != null) actions++;
        if (clearCache) actions++;
        if (listQueries) actions++;
        if (describeTable != null) actions++;
        if (deleteQueryName != null) actions++;
        if (saveQueryName != null) actions++;
        if (runQueryName != null) actions++;
        if (addIndex != null) actions++;
        if (dropIndex != null) actions++;
        if (listIndexes) actions++;
        if (rebuildIndex != null) actions++;
        if (rebuildIndexes) actions++;
        if (materializeViewName != null) actions++;
        if (listMaterializedViews) actions++;
        if (showViewName != null) actions++;
        if (dropMaterializedViewName != null) actions++;
        if (rebuildViewName != null) actions++;
        if (rebuildViews) actions++;
        // Inline query execution counts as an action only when not used as a save/materialize payload
        if (query != null && saveQueryName == null && runQueryName == null
            && materializeViewName == null) actions++;

        if (actions > 1) {
            return "Multiple actions specified. Use only one of --query, --run-query, --save-query, "
                + "--delete-query, --list-tables, --describe, --list-queries, --add-mapping, "
                + "--clear-cache, --add-index, --drop-index, --list-indexes, --rebuild-index, "
                + "--rebuild-indexes, --materialize-view, --list-materialized-views, --show-view, "
                + "--drop-materialized-view, --rebuild-view, or --rebuild-views at a time.";
        }

        if (runQueryName != null && query != null) {
            return "Cannot combine --run-query with --query. Use one or the other.";
        }

        return null;
    }
    
    /**
     * List all saved queries in a formatted table.
     */
    private void listSavedQueries(QueryManager queryManager) {
        Map<String, String> queries = queryManager.getAllQueries();
        
        if (queries.isEmpty()) {
            System.out.println("No saved queries found.");
            System.out.println("Use --save-query <name> --query <sql> to save a query");
            return;
        }
        
        System.out.println("Saved Queries:");
        System.out.println("─".repeat(80));
        
        for (Map.Entry<String, String> entry : queries.entrySet()) {
            String name = entry.getKey();
            String sql = entry.getValue();
            
            System.out.printf("  %-20s -> %s%n", name, sql);
        }
        
        System.out.println("─".repeat(80));
        System.out.println("Total: " + queries.size() + " saved quer" + (queries.size() == 1 ? "y" : "ies"));
    }
    
    /**
     * Handle the declared-index management commands (add/drop/list/rebuild).
     */
    private Integer handleIndexCommands(MappingManager mappingManager) {
        String dataDirError = validateDataDirectory();
        if (dataDirError != null) {
            System.err.println("Error: " + dataDirError);
            return 1;
        }
        IndexManager indexManager;
        try {
            indexManager = new IndexManager(indexesFile);
        } catch (RuntimeException e) {
            System.err.println("Error loading index definitions: " + e.getMessage());
            return 1;
        }
        IndexStore indexStore = new IndexStore(dataDirectory);
        MaterializedViewManager viewManager = loadViewManagerQuietly();
        MaterializedViewStore viewStore = viewManager != null
            ? new MaterializedViewStore(dataDirectory) : null;
        IndexBuilder builder = new IndexBuilder(mappingManager, dataDirectory, indexStore,
            viewManager, viewStore);

        if (addIndex != null) {
            String table = addIndex[0];
            String field = addIndex[1];
            boolean isView = viewManager != null && viewManager.hasView(table);
            if (!mappingManager.hasMapping(table) && !isView) {
                System.err.println("Error: No mapping or materialized view found for table: " + table);
                return 1;
            }
            try {
                boolean added = indexManager.addIndex(table, field);
                TableIndex idx = builder.build(table, field);
                System.out.println((added ? "Index added: " : "Index already declared, rebuilt: ")
                    + table + "." + field);
                System.out.println(summarize(idx));
                return 0;
            } catch (Exception e) {
                System.err.println("Error building index: " + e.getMessage());
                if (isDebugEnabled()) {
                    e.printStackTrace();
                }
                return 1;
            }
        }

        if (dropIndex != null) {
            String table = dropIndex[0];
            String field = dropIndex[1];
            boolean removed = indexManager.dropIndex(table, field);
            indexStore.delete(table, field);
            System.out.println(removed
                ? "Index dropped: " + table + "." + field
                : "No such index: " + table + "." + field);
            return 0;
        }

        if (listIndexes) {
            listDeclaredIndexes(indexManager, indexStore, mappingManager);
            return 0;
        }

        if (rebuildIndex != null) {
            String table = rebuildIndex[0];
            String field = rebuildIndex[1];
            if (!indexManager.hasIndex(table, field)) {
                System.err.println("Error: No declared index: " + table + "." + field);
                return 1;
            }
            try {
                TableIndex idx = builder.build(table, field);
                System.out.println("Rebuilt index: " + table + "." + field);
                System.out.println(summarize(idx));
                return 0;
            } catch (Exception e) {
                System.err.println("Error rebuilding index: " + e.getMessage());
                if (isDebugEnabled()) {
                    e.printStackTrace();
                }
                return 1;
            }
        }

        if (rebuildIndexes) {
            List<IndexDefinition> defs = indexManager.getAllIndexes();
            if (defs.isEmpty()) {
                System.out.println("No declared indexes to rebuild.");
                return 0;
            }
            int ok = 0;
            int failed = 0;
            for (IndexDefinition def : defs) {
                try {
                    builder.build(def.getTable(), def.getField());
                    System.out.println("Rebuilt: " + def);
                    ok++;
                } catch (Exception e) {
                    System.err.println("Failed: " + def + " - " + e.getMessage());
                    failed++;
                }
            }
            System.out.println("Rebuilt " + ok + " index(es)"
                + (failed > 0 ? ", " + failed + " failed" : "") + ".");
            return failed > 0 ? 1 : 0;
        }

        return 0;
    }

    private String summarize(TableIndex idx) {
        return "  kind=" + idx.getKind() + ", files=" + idx.getFiles().size();
    }

    private void listDeclaredIndexes(IndexManager indexManager, IndexStore indexStore,
                                     MappingManager mappingManager) {
        List<IndexDefinition> defs = indexManager.getAllIndexes();
        if (defs.isEmpty()) {
            System.out.println("No declared indexes.");
            System.out.println("Add one with: jsonsql --add-index <table> <field>");
            return;
        }
        System.out.println("Declared indexes:");
        System.out.println("─".repeat(72));
        for (IndexDefinition def : defs) {
            TableIndex idx = indexStore.load(def.getTable(), def.getField());
            String status = indexFreshness(def, idx, mappingManager);
            String detail = idx == null
                ? "(not built)"
                : idx.getKind() + ", " + idx.getFiles().size() + " file(s)";
            System.out.printf("  %-32s -> %-26s [%s]%n", def.toString(), detail, status);
        }
        System.out.println("─".repeat(72));
        System.out.println("Total: " + defs.size() + " index(es)");
    }

    /**
     * Determine whether a built index still matches the current backing files.
     */
    private String indexFreshness(IndexDefinition def, TableIndex idx, MappingManager mappingManager) {
        if (idx == null || !mappingManager.hasMapping(def.getTable())) {
            return "STALE";
        }
        String jsonPath = mappingManager.getJsonPathOnly(def.getTable());
        if (idx.getJsonPath() != null && jsonPath != null && !idx.getJsonPath().equals(jsonPath)) {
            return "STALE";
        }
        try {
            TableFileResolver resolver = new TableFileResolver(mappingManager, dataDirectory);
            File base = resolver.resolveBase(def.getTable());
            List<File> files = resolver.resolveFiles(def.getTable());
            if (files.size() != idx.getFiles().size()) {
                return "STALE";
            }
            for (File f : files) {
                FileSummary match = null;
                String canon = TableFileResolver.canonicalPath(f);
                for (FileSummary s : idx.getFiles()) {
                    if (canon.equals(s.getCanonicalPath())) {
                        match = s;
                        break;
                    }
                }
                if (match == null) {
                    String rel = TableFileResolver.relativePath(base, f);
                    for (FileSummary s : idx.getFiles()) {
                        if (rel.equals(s.getRelPath())) {
                            match = s;
                            break;
                        }
                    }
                }
                if (match == null || match.getMtime() != f.lastModified() || match.getSize() != f.length()) {
                    return "STALE";
                }
            }
            return "FRESH";
        } catch (Exception e) {
            return "STALE";
        }
    }

    /**
     * Handle materialized view management commands.
     */
    private Integer handleViewCommands(MappingManager mappingManager) {
        String dataDirError = validateDataDirectory();
        if (dataDirError != null) {
            System.err.println("Error: " + dataDirError);
            return 1;
        }
        MaterializedViewManager viewManager;
        try {
            viewManager = new MaterializedViewManager(viewsFile);
        } catch (RuntimeException e) {
            System.err.println("Error loading materialized views: " + e.getMessage());
            return 1;
        }
        MaterializedViewStore viewStore = new MaterializedViewStore(dataDirectory);
        IndexManager indexManager = loadIndexManagerQuietly();
        MaterializedViewBuilder builder = new MaterializedViewBuilder(
            mappingManager, dataDirectory, viewManager, viewStore);

        if (materializeViewName != null) {
            if (query == null) {
                System.err.println("Error: --query is required with --materialize-view");
                return 1;
            }
            try {
                MaterializedViewDefinition def = builder.materialize(materializeViewName, query, indexManager);
                System.out.println("Materialized view created: " + def.getName());
                System.out.println("  SQL: " + def.getCteSql());
                System.out.println("  rows=" + def.getRowCount());
                return 0;
            } catch (Exception e) {
                System.err.println("Error materializing view: " + e.getMessage());
                if (isDebugEnabled()) {
                    e.printStackTrace();
                }
                return 1;
            }
        }

        if (dropMaterializedViewName != null) {
            boolean removed = viewManager.dropView(dropMaterializedViewName);
            viewStore.delete(dropMaterializedViewName);
            if (indexManager != null) {
                for (IndexDefinition idx : indexManager.getIndexesForTable(dropMaterializedViewName)) {
                    indexManager.dropIndex(idx.getTable(), idx.getField());
                    new IndexStore(dataDirectory).delete(idx.getTable(), idx.getField());
                }
            }
            System.out.println(removed
                ? "Materialized view dropped: " + dropMaterializedViewName
                : "No such materialized view: " + dropMaterializedViewName);
            return 0;
        }

        if (listMaterializedViews) {
            listMaterializedViews(viewManager, mappingManager);
            return 0;
        }

        if (showViewName != null) {
            showMaterializedView(showViewName, viewManager, mappingManager, indexManager);
            return 0;
        }

        if (rebuildViewName != null) {
            if (!viewManager.hasView(rebuildViewName)) {
                System.err.println("Error: No materialized view: " + rebuildViewName);
                return 1;
            }
            try {
                MaterializedViewDefinition def = builder.rebuild(rebuildViewName, indexManager);
                System.out.println("Rebuilt materialized view: " + def.getName());
                System.out.println("  rows=" + def.getRowCount());
                return 0;
            } catch (Exception e) {
                System.err.println("Error rebuilding view: " + e.getMessage());
                if (isDebugEnabled()) {
                    e.printStackTrace();
                }
                return 1;
            }
        }

        if (rebuildViews) {
            List<MaterializedViewDefinition> defs = viewManager.getAllViews();
            if (defs.isEmpty()) {
                System.out.println("No materialized views to rebuild.");
                return 0;
            }
            int ok = 0;
            int failed = 0;
            for (MaterializedViewDefinition def : defs) {
                try {
                    builder.rebuild(def.getName(), indexManager);
                    System.out.println("Rebuilt: " + def.getName());
                    ok++;
                } catch (Exception e) {
                    System.err.println("Failed: " + def.getName() + " - " + e.getMessage());
                    failed++;
                }
            }
            System.out.println("Rebuilt " + ok + " view(s)"
                + (failed > 0 ? ", " + failed + " failed" : "") + ".");
            return failed > 0 ? 1 : 0;
        }

        return 0;
    }

    private void listMaterializedViews(MaterializedViewManager viewManager, MappingManager mappingManager) {
        List<MaterializedViewDefinition> defs = viewManager.getAllViews();
        if (defs.isEmpty()) {
            System.out.println("No materialized views.");
            System.out.println("Create one with: jsonsql --materialize-view <name> --query \"WITH <name> AS (...) SELECT ...\"");
            return;
        }
        System.out.println("Materialized views:");
        System.out.println("─".repeat(80));
        for (MaterializedViewDefinition def : defs) {
            SourceFingerprint.warnIfDataDirectoryMismatch(def, dataDirectory);
            String status = SourceFingerprint.isFresh(def, mappingManager, dataDirectory) ? "FRESH" : "STALE";
            System.out.printf("  %-20s -> %s%n", def.getName(), def.getCteSql());
            System.out.printf("                         rows=%d  built=%s  [%s]%n",
                def.getRowCount(), def.getBuiltAt(), status);
        }
        System.out.println("─".repeat(80));
        System.out.println("Total: " + defs.size() + " materialized view(s)");
    }

    private void showMaterializedView(String name, MaterializedViewManager viewManager,
                                      MappingManager mappingManager, IndexManager indexManager) {
        MaterializedViewDefinition def = viewManager.getView(name);
        if (def == null) {
            System.err.println("No materialized view: " + name);
            return;
        }
        SourceFingerprint.warnIfDataDirectoryMismatch(def, dataDirectory);
        String status = SourceFingerprint.isFresh(def, mappingManager, dataDirectory) ? "FRESH" : "STALE";
        System.out.println("Materialized view: " + def.getName());
        System.out.println("  SQL: " + def.getCteSql());
        System.out.println("  Rows: " + def.getRowCount());
        System.out.println("  Built: " + def.getBuiltAt());
        System.out.println("  Status: " + status);
        if (indexManager != null) {
            List<IndexDefinition> indexes = indexManager.getIndexesForTable(name);
            if (!indexes.isEmpty()) {
                System.out.println("  Indexes:");
                for (IndexDefinition idx : indexes) {
                    System.out.println("    - " + idx.getField());
                }
            }
        }
    }

    /**
     * Load materialized view definitions for query-time resolution.
     */
    private MaterializedViewManager loadViewManagerQuietly() {
        try {
            MaterializedViewManager manager = new MaterializedViewManager(viewsFile);
            return manager.size() > 0 ? manager : null;
        } catch (RuntimeException e) {
            System.err.println("Warning: ignoring materialized view definitions (" + e.getMessage() + ")");
            return null;
        }
    }

    /**
     * Load declared indexes for query-time pruning. Returns null when none are declared
     * or the definitions file is unreadable (queries should still run via full scan).
     */
    private IndexManager loadIndexManagerQuietly() {
        try {
            IndexManager indexManager = new IndexManager(indexesFile);
            return indexManager.size() > 0 ? indexManager : null;
        } catch (RuntimeException e) {
            System.err.println("Warning: ignoring index definitions (" + e.getMessage() + ")");
            return null;
        }
    }

    private OutputFormat parseOutputFormat(String value) {
        if (value == null || value.isBlank()) {
            return OutputFormat.JSON;
        }
        return switch (value.trim().toLowerCase()) {
            case "json" -> OutputFormat.JSON;
            case "csv" -> OutputFormat.CSV;
            default -> throw new IllegalArgumentException(
                "Unsupported output format: '" + value + "'. Supported values: json, csv");
        };
    }

    /**
     * Parse parameter list from CLI arguments.
     * Expected format: key=value
     * 
     * @param paramList List of parameter strings in "key=value" format
     * @return Map of parameter names to values
     * @throws IllegalArgumentException if parameter format is invalid
     */
    private Map<String, String> parseParameters(List<String> paramList) {
        Map<String, String> paramMap = new HashMap<>();
        
        if (paramList == null || paramList.isEmpty()) {
            return paramMap;
        }
        
        for (String param : paramList) {
            if (param == null || param.trim().isEmpty()) {
                continue;
            }
            
            int equalsIndex = param.indexOf('=');
            if (equalsIndex < 0) {
                throw new IllegalArgumentException(
                    "Invalid parameter format: '" + param + "'. Expected format: key=value"
                );
            }
            
            String key = param.substring(0, equalsIndex).trim();
            String value = param.substring(equalsIndex + 1).trim();
            
            if (key.isEmpty()) {
                throw new IllegalArgumentException(
                    "Parameter name cannot be empty. Format: key=value"
                );
            }
            
            paramMap.put(key, value);
        }
        
        return paramMap;
    }
}

