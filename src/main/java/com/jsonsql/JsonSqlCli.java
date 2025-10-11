package com.jsonsql;

import com.jsonsql.config.MappingManager;
import com.jsonsql.output.OutputHandler;
import com.jsonsql.query.QueryExecutor;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
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

    public static void main(String[] args) {
        int exitCode = new CommandLine(new JsonSqlCli()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        MappingManager mappingManager = new MappingManager(configFile);

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
        System.err.println("Please provide a query (--query) or use --list-tables or --add-mapping");
        return 1;
    }
}

