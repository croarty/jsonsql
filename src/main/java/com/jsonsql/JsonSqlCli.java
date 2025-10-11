package com.jsonsql;

import com.jsonsql.config.MappingManager;
import com.jsonsql.config.QueryManager;
import com.jsonsql.output.OutputHandler;
import com.jsonsql.query.QueryExecutor;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.util.Map;
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

    public static void main(String[] args) {
        int exitCode = new CommandLine(new JsonSqlCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        MappingManager mappingManager = new MappingManager(configFile);
        QueryManager queryManager = new QueryManager(queriesFile);

        // Handle list-tables command
        if (listTables) {
            mappingManager.listMappings();
            return 0;
        }

        // Handle add-mapping command
        if (addMapping != null && addMapping.length == 2) {
            String alias = addMapping[0];
            String jsonPath = addMapping[1];
            mappingManager.addMapping(alias, jsonPath);
            System.out.println("Mapping added: " + alias + " -> " + jsonPath);
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
            System.out.println("Running saved query: " + runQueryName);
            System.out.println("SQL: " + query);
            System.out.println();
            // Fall through to execute the query
        }

        // Handle query execution
        if (query != null) {
            try {
                QueryExecutor executor = new QueryExecutor(mappingManager, dataDirectory);
                String result = executor.execute(query);
                
                OutputHandler outputHandler = new OutputHandler(prettyPrint);
                outputHandler.handleOutput(result, outputFile, clipboard);
                
                return 0;
            } catch (Exception e) {
                System.err.println("Error executing query: " + e.getMessage());
                if (System.getenv("DEBUG") != null) {
                    e.printStackTrace();
                }
                return 1;
            }
        }

        // No valid command provided
        System.err.println("Please provide a query (--query), use --run-query, or use --list-tables/--list-queries");
        return 1;
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
}

