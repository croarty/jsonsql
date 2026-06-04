package com.jsonsql;

import com.jsonsql.config.CacheManager;
import com.jsonsql.config.MappingManager;
import com.jsonsql.config.QueryManager;
import com.jsonsql.config.QueryParameterReplacer;
import com.jsonsql.output.OutputHandler;
import com.jsonsql.query.QueryExecutor;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.*;
import java.util.concurrent.Callable;

@Command(
    name = "jsonsql",
    mixinStandardHelpOptions = true,
    version = "JsonSQL 1.0.0",
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
                QueryExecutor executor = new QueryExecutor(mappingManager, dataDirectory, cacheManager);
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
                OutputHandler outputHandler = new OutputHandler(prettyPrint);
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
        System.err.println("  --list-queries          List saved queries");
        System.err.println("  --add-mapping <a> <p>   Add a JSONPath mapping");
        System.err.println("  --clear-cache           Clear cached data");
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
        if (deleteQueryName != null) actions++;
        if (saveQueryName != null) actions++;
        if (runQueryName != null) actions++;
        // Inline query execution counts as an action only when not used as a save payload
        // and not combined with run-query (handled separately below).
        if (query != null && saveQueryName == null && runQueryName == null) actions++;

        if (actions > 1) {
            return "Multiple actions specified. Use only one of --query, --run-query, --save-query, "
                + "--delete-query, --list-tables, --list-queries, --add-mapping, or --clear-cache at a time.";
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

